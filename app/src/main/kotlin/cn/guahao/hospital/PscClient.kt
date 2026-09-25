package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.io.IOException

class PscTransport(cookieJar: CookieJar, private val gate: Mutex,
    private val baseUrl: HttpUrl = "https://psc.hkinfo.net/".toHttpUrl()) {
    private val client = OkHttpClient.Builder().cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    suspend fun get(path: String, query: Map<String, String>) = call(path, query, null)
    suspend fun post(path: String, values: Map<String, String>): JsonObject = responseObject(call(path, emptyMap(),
        buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString()))
    private suspend fun call(path: String, query: Map<String, String>, body: String?): String = gate.withLock {
        withContext(Dispatchers.IO) {
            require(path.startsWith('/') && !path.startsWith("//") && !path.contains('?'))
            val url = baseUrl.newBuilder().encodedPath(path).apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
            val request = Request.Builder().url(url).header("Accept", "application/json,text/html")
                .header("User-Agent", "Guahao/0.1 (Android; personal appointment client)")
                .apply { if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType())) }.build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399) throw HospitalException("医院要求重新登录或跳转，请重新接入服务号")
                    if (response.code == 429 || response.code == 503) {
                        val wait = response.header("Retry-After")?.let { raw -> raw.toLongOrNull()?.takeIf { it >= 0 }?.let { Math.multiplyExact(it, 1000) }
                            ?: runCatching { java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis().coerceAtLeast(0) }.getOrNull() }
                        throw HospitalException("医院暂忙，请稍后再试", wait, true)
                    }
                    if (!response.isSuccessful) throw HospitalException("医院请求未成功，请稍后再试", retryable = response.code >= 500)
                    val source = response.body?.source() ?: throw HospitalException("医院返回空响应")
                    if (source.request(4L * 1024 * 1024 + 1)) throw HospitalException("医院响应过大，已停止处理")
                    source.readUtf8()
                }
            } catch (_: IOException) { throw HospitalException("网络连接失败", retryable = true) }
        }
    }
}

fun sessionQuery(s: PscSession) = mapOf("userId" to s.userId, "userIdKey" to s.userKey, "ptno" to s.ptno)
fun lockPayload(s: PscSession, t: BookingTask, c: Candidate) = mapOf(
    "userId" to s.userId, "userIdKey" to s.userKey,
    "dept_code1" to c.department.parentCode, "dept_code2" to c.department.code,
    "purpose" to t.condition.purpose, "dept_nm1" to c.department.parentName, "dept_nm2" to c.department.name,
    "reg_date" to c.date.format(DateTimeFormatter.BASIC_ISO_DATE), "reg_half" to c.half,
    "reg_hour" to c.hour, "doctor_code" to c.doctorCode, "title_type" to c.titleType,
    "iscanceled" to "0", "doctor" to c.doctorName, "dept_code2_from" to c.department.originCode
)
fun insurancePayload(s: PscSession, order: String) = mapOf("userId" to s.userId,
    "userIdKey" to URLEncoder.encode(s.userKey, "UTF-8").replace("+", "%20"), "orderNo" to order)

class PscClient(private val sessions: SessionRepository) : BookingGateway {
    private fun pair(ref: PatientRef) = sessions.load(ref).let { it to sessions.transport(ref.sessionId) }
    override suspend fun departments(patient: PatientRef): List<DepartmentRef> {
        val (s, http) = pair(patient)
        return PscPageParser.departments(http.get("/regis/initDept", sessionQuery(s)))
    }
    override suspend fun candidates(condition: VisitCondition): List<Candidate> {
        val (s, http) = pair(condition.patient)
        val d = condition.department
        return PscPageParser.schedule(http.get("/regis/initRegis", sessionQuery(s) + mapOf(
            "deptCode1" to d.parentCode, "deptCode2" to d.code, "deptNm1" to d.parentName,
            "deptNm2" to d.name, "purpose" to condition.purpose, "oriDeptTwo" to d.originCode)), d).flatMap { it.candidates }
    }
    private suspend fun access(patient: PatientRef, function: String): Boolean {
        val (s, http) = pair(patient)
        val reply = http.post("/function/functionControl", mapOf("functionid" to function, "ptno" to s.ptno, "ptnoKey" to s.ptnoKey))
        return reply.text("code") == "0"
    }
    override suspend fun validateBookingAccess(patient: PatientRef) = access(patient, "002")
    override suspend fun insuranceAvailable(patient: PatientRef) = access(patient, "001")
    override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot> {
        val (s, http) = pair(patient)
        return parseOrders(http.post("/regis/getRegisList", mapOf("ptno" to s.ptno, "ptnoKey" to s.ptnoKey,
            "actdate" to from.toString(), "enddate" to to.toString())), patient)
    }
    override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply {
        val (s, http) = pair(task.condition.patient)
        require(!task.demo && candidate.remaining > 0 && !candidate.standby)
        return decodeLockCode(http.post("/regis/lockRegis", lockPayload(s, task, candidate)).text("code"))
    }
    override suspend fun querySubmission(patient: PatientRef): AsyncReply {
        val (s, http) = pair(patient)
        return decodeAsync(http.post("/regis/queryRegisStat", mapOf("userId" to s.userId)))
    }
    override suspend fun initializeInsurance(patient: PatientRef, orderNo: String): InsuranceReply {
        val (s, http) = pair(patient)
        val result = http.post("/order/sxPayCN", insurancePayload(s, orderNo))
        if (result.text("code") != "0") return InsuranceReply.REJECTED
        val data = responseObject(result.text("data"))
        // Temporary authorization values are validated in memory and never persisted or opened as a URL.
        if (listOf("pageAuthCode", "sceneId", "transId").any { data.optional(it).isNullOrBlank() }) return InsuranceReply.UNKNOWN
        return InsuranceReply.ACCEPTED
    }
    override suspend fun paymentState(patient: PatientRef, orderNo: String): InsuranceReply {
        val (s, http) = pair(patient)
        return when(http.post("/order/queryRegisYbPayState", insurancePayload(s, orderNo)).text("code")) {
            "0" -> InsuranceReply.PAID; "2" -> InsuranceReply.PENDING; else -> InsuranceReply.UNKNOWN
        }
    }
}
