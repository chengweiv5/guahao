package cn.guahao.hospital

import cn.guahao.core.*
import cn.guahao.core.psc.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val baseUrl: HttpUrl = "https://psc.hkinfo.net/".toHttpUrl(),
    private val budget: RequestBudget = RequestBudget()) {
    private val client = OkHttpClient.Builder().cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    suspend fun get(path: String, query: Map<String, String>) = call(path, query, null, { true })
    suspend fun post(path: String, values: Map<String, String>, beforeSend: () -> Boolean = { true }): JsonObject = responseObject(call(path, emptyMap(),
        buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString(), beforeSend))
    private suspend fun call(path: String, query: Map<String, String>, body: String?, beforeSend: () -> Boolean): String = gate.withLock {
        budget.awaitTurn()
        withContext(Dispatchers.IO) {
            require(path.startsWith('/') && !path.startsWith("//") && !path.contains('?'))
            val url = baseUrl.newBuilder().encodedPath(path).apply { query.forEach { (k,v) -> addQueryParameter(k,v) } }.build()
            val request = Request.Builder().url(url).header("Accept", "application/json,text/html")
                .header("User-Agent", "Guahao/0.1 (Android; personal appointment client)")
                .apply { if (body != null) post(body.toRequestBody("application/json; charset=utf-8".toMediaType())) }.build()
            try {
                if (!beforeSend()) throw HospitalException("已停止或超过原提交期限，未发送请求")
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) throw HospitalException("医院要求重新登录，请重新连接服务号", reconnectRequired = true)
                    if (response.code in 300..399) {
                        val target = response.header("Location")?.let { url.resolve(it) }
                        val login = target?.scheme == "https" && target.host == "open.weixin.qq.com" &&
                            target.encodedPath == "/connect/oauth2/authorize"
                        throw HospitalException(if (login) "医院要求微信授权，请重新连接服务号" else "医院页面发生跳转，请重试校验或到微信核对",
                            reconnectRequired = login)
                    }
                    if (response.code == 429 || response.code == 503) {
                        val wait = response.header("Retry-After")?.let { raw -> raw.toLongOrNull()?.takeIf { it >= 0 }?.let { Math.multiplyExact(it, 1000) }
                            ?: runCatching { java.time.Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis().coerceAtLeast(0) }.getOrNull() }
                        budget.defer(wait ?: 5000)
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

class PscClient(private val sessions: SessionRepository, private val maySubmit: (BookingTask) -> Boolean = { true }) : BookingGateway {
    override fun binding(patient: PatientRef) = sessions.identities.resolve(patient)
        ?: throw HospitalException("连接身份待核对，请重新连接医院")
    private suspend fun <T> withSession(ref: PatientRef, block: suspend (PscSession, PscTransport) -> T): T = try {
        block(sessions.load(ref), sessions.transport(ref.sessionId))
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { sessions.recordFailure(ref, e); throw e }
    override suspend fun departments(patient: PatientRef): List<DepartmentRef> = withSession(patient) { s, http ->
        PscPageParser.departments(http.get("/regis/initDept", sessionQuery(s)))
    }
    override suspend fun candidates(condition: VisitCondition): List<Candidate> = withSession(condition.patient) { s, http ->
        val d = condition.department
        PscPageParser.schedule(http.get("/regis/initRegis", sessionQuery(s) + mapOf(
            "deptCode1" to d.parentCode, "deptCode2" to d.code, "deptNm1" to d.parentName,
            "deptNm2" to d.name, "purpose" to condition.purpose, "oriDeptTwo" to d.originCode)), d).flatMap { it.candidates }
    }
    private suspend fun access(patient: PatientRef, function: String): Boolean = withSession(patient) { s, http ->
        val reply = http.post("/function/functionControl", mapOf("functionid" to function, "ptno" to s.ptno, "ptnoKey" to s.ptnoKey))
        val available = reply.text("code") == "0"
        if (function == "002") sessions.recordAccess(patient, available)
        available
    }
    override suspend fun validateBookingAccess(patient: PatientRef) = access(patient, "002")
    override suspend fun insuranceAvailable(patient: PatientRef) = access(patient, "001")
    override suspend fun orders(patient: PatientRef, from: LocalDate, to: LocalDate): List<OrderSnapshot> = withSession(patient) { s, http ->
        parseOrders(http.post("/regis/getRegisList", mapOf("ptno" to s.ptno, "ptnoKey" to s.ptnoKey,
            "actdate" to from.toString(), "enddate" to to.toString())), patient)
    }
    override suspend fun lock(task: BookingTask, candidate: Candidate): LockReply = withSession(task.condition.patient) { s, http ->
        require(!task.demo && candidate.remaining > 0 && !candidate.standby)
        decodeLockCode(http.post("/regis/lockRegis", lockPayload(s, task, candidate)) { java.time.Instant.now().isBefore(task.deadline) && maySubmit(task) }.text("code"))
    }
    override suspend fun querySubmission(patient: PatientRef): AsyncReply = withSession(patient) { s, http ->
        decodeAsync(http.post("/regis/queryRegisStat", mapOf("userId" to s.userId)))
    }
    override suspend fun initializeInsurance(patient: PatientRef, orderNo: String): InsuranceReply = withSession(patient) { s, http ->
        val result = http.post("/order/sxPayCN", insurancePayload(s, orderNo))
        if (result.text("code") != "0") return@withSession InsuranceReply.REJECTED
        val data = responseObject(result.text("data"))
        // Temporary authorization values are validated in memory and never persisted or opened as a URL.
        if (listOf("pageAuthCode", "sceneId", "transId").any { data.optional(it).isNullOrBlank() }) return@withSession InsuranceReply.UNKNOWN
        InsuranceReply.ACCEPTED
    }
    override suspend fun paymentState(patient: PatientRef, orderNo: String): InsuranceReply = withSession(patient) { s, http ->
        when(http.post("/order/queryRegisYbPayState", insurancePayload(s, orderNo)).text("code")) {
            "0" -> InsuranceReply.PAID; "2" -> InsuranceReply.PENDING; else -> InsuranceReply.UNKNOWN
        }
    }
}
