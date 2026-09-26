package cn.guahao.hospital.beijing

import cn.guahao.core.*
import cn.guahao.hospital.RequestBudget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BeijingHospital(val code: String, val name: String, val level: String?, val releaseDescription: String?)
data class BeijingHospitalPage(val hospitals: List<BeijingHospital>, val total: Int)
data class BeijingCalendarDay(val date: LocalDate, val availability: DateAvailability, val officialLabel: String)

enum class BeijingFailureKind { CLIENT_VERIFICATION, RECONNECT, RATE_LIMITED, INVALID_RESPONSE, BUSINESS, NETWORK }
class BeijingQueryException(val kind: BeijingFailureKind) : Exception(when (kind) {
    BeijingFailureKind.CLIENT_VERIFICATION -> "请先打开此渠道的官方连接页，完成登录或校验后重试"
    BeijingFailureKind.RECONNECT -> "此渠道需要重新登录，请在原渠道核对"
    BeijingFailureKind.RATE_LIMITED -> "平台请求繁忙，请稍后再试"
    BeijingFailureKind.INVALID_RESPONSE -> "平台返回的数据暂不可确认，请稍后重试"
    BeijingFailureKind.BUSINESS -> "平台暂未完成本次查询，请在官方页面核对"
    BeijingFailureKind.NETWORK -> "暂时无法连接平台，请检查网络后重试"
})

/** Public queries only. No cookie import, authentication, submission or challenge emulation. */
class BeijingQueryClient internal constructor(
    private val baseUrl: HttpUrl,
    client: OkHttpClient,
    private val intervalMillis: Long,
    private val transport: BeijingQueryTransport? = null
) {
    constructor() : this("https://www.114yygh.com/jtjk/mobile-service/".toHttpUrl(), OkHttpClient(), 1000)
    constructor(transport: BeijingQueryTransport) : this(
        "https://www.114yygh.com/jtjk/mobile-service/".toHttpUrl(), OkHttpClient(), 1000, transport)

    private val client = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
        .followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).callTimeout(java.time.Duration.ofSeconds(25)).build()
    private val gate = Mutex()
    private val budget = RequestBudget(intervalMillis)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hospitals(channel: RegistrationChannel, page: Int = 1): BeijingHospitalPage {
        require(page > 0)
        val payload = buildJsonObject {
            listOf("hospitalTypeList", "releaseTimeList", "levelList", "serviceInfoList").forEach { put(it, JsonArray(emptyList())) }
            put("areaInfo", 0); put("sortType", 0); put("longitude", ""); put("latitude", "")
            put("pageNo", page); put("pageSize", 15); put("total", 0)
        }
        val data = request(channel, "hospital/list", payload).objectValue()
        val rows = data["list"]?.arrayValue() ?: invalid()
        val hospitals = rows.map { row ->
            val item = row.objectValue()
            BeijingHospital(item.requiredString("code"), item.requiredString("name"),
                item.optionalString("levelText"), item.optionalString("openTimeText"))
        }
        val count = (data["count"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: invalid()
        return BeijingHospitalPage(hospitals.distinctBy { it.code }, count)
    }

    suspend fun departments(channel: RegistrationChannel, hospital: String): List<DepartmentRef> {
        require(hospital.isNotBlank())
        return request(channel, "department/list", suffix = hospital).arrayValue().flatMap { parent ->
            val group = parent.objectValue()
            val code = group.requiredString("code")
            val name = group.requiredString("name")
            val children = group["subList"]?.takeUnless { it == JsonNull }?.arrayValue().orEmpty()
            children.map { child ->
                val item = child.objectValue()
                if (item.requiredString("parentCode") != code) invalid()
                DepartmentRef(code, item.requiredString("code"), name, item.requiredString("name"), "")
            }
        }.distinctBy { it.parentCode to it.code }
    }

    suspend fun calendar(channel: RegistrationChannel, hospital: String, department: DepartmentRef): List<BeijingCalendarDay> {
        require(hospital.isNotBlank() && department.parentCode.isNotBlank() && department.code.isNotBlank())
        val data = request(channel, "product/calendar", buildJsonObject {
            put("hosCode", hospital); put("firstDeptCode", department.parentCode); put("secondDeptCode", department.code)
        }).objectValue()
        val days = (data["calendars"] ?: invalid()).arrayValue().map { row ->
            val item = row.objectValue()
            val rawDate = item.requiredString("dutyDate")
            val date = runCatching { LocalDate.parse(rawDate,
                if (rawDate.length == 8) DateTimeFormatter.BASIC_ISO_DATE else DateTimeFormatter.ISO_LOCAL_DATE) }.getOrElse { invalid() }
            val label = item.requiredString("statusView")
            val availability = when (label) {
                "有号" -> DateAvailability.AVAILABLE
                "无号", "约满" -> DateAvailability.NO_STOCK
                "即将放号", "尚未放号", "未放号" -> DateAvailability.NOT_RELEASED
                else -> DateAvailability.UNKNOWN
            }
            BeijingCalendarDay(date, availability, label)
        }
        if (days.map { it.date }.distinct().size != days.size) invalid()
        return days
    }

    suspend fun doctors(channel: RegistrationChannel, hospital: String, department: DepartmentRef,
        date: LocalDate): BeijingDoctorSchedule {
        require(hospital.isNotBlank() && department.parentCode.isNotBlank() && department.code.isNotBlank())
        val data = request(channel, "product/doctor/detail", buildJsonObject {
            put("hosCode", hospital); put("firstDeptCode", department.parentCode); put("secondDeptCode", department.code)
            put("dutyDate", date.format(DateTimeFormatter.BASIC_ISO_DATE)); put("type", ""); put("dutyCode", "")
        })
        return BeijingDoctorParser.parse(data, department, date)
    }

    private suspend fun request(channel: RegistrationChannel, path: String, body: JsonObject? = null, suffix: String? = null): JsonElement = gate.withLock {
        val query = BeijingQueryRequest(channel, path, body, suffix)
        BeijingQueryPolicy.validate(query)
        val source = requireNotNull(channel.requestSource) { "北京平台查询不接受其他渠道" }
        budget.awaitTurn()
        val url = baseUrl.newBuilder().addPathSegments(path).apply { suffix?.let(::addPathSegment) }
            .addQueryParameter("_time", System.currentTimeMillis().toString()).build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Request-Source", source)
            .apply { body?.let { post(it.toString().toRequestBody("application/json;charset=UTF-8".toMediaType())) } }.build()
        val reply = transport?.execute(query) ?: execute(request)
        if (reply.code == 429) { budget.defer(30_000); throw BeijingQueryException(BeijingFailureKind.RATE_LIMITED) }
        if (reply.code == 401) throw BeijingQueryException(BeijingFailureKind.RECONNECT)
        if (reply.type.contains("text/html", true) || reply.code in setOf(202, 467)) throw BeijingQueryException(BeijingFailureKind.CLIENT_VERIFICATION)
        if (reply.code != 200 || !reply.type.contains("application/json", true)) invalid()
        val root = runCatching { json.parseToJsonElement(reply.body).objectValue() }.getOrElse { invalid() }
        when (root.requiredString("code")) {
            "0000" -> root["data"]?.takeUnless { it == JsonNull } ?: invalid()
            "0001", "3087", "102" -> throw BeijingQueryException(BeijingFailureKind.RECONNECT)
            "429" -> { budget.defer(30_000); throw BeijingQueryException(BeijingFailureKind.RATE_LIMITED) }
            else -> throw BeijingQueryException(BeijingFailureKind.BUSINESS)
        }
    }

    private suspend fun execute(request: Request): BeijingQueryReply = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(BeijingQueryException(BeijingFailureKind.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val body = it.body ?: invalid()
                        val source = body.source()
                        source.request(1_048_577)
                        if (source.buffer.size > 1_048_576) invalid()
                        BeijingQueryReply(it.code, it.header("Content-Type").orEmpty(), source.readUtf8())
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume) {
                    continuation.resumeWithException(it as? BeijingQueryException ?: BeijingQueryException(BeijingFailureKind.NETWORK))
                }
            }
        })
    }

    private fun JsonElement.objectValue() = this as? JsonObject ?: invalid()
    private fun JsonElement.arrayValue() = this as? JsonArray ?: invalid()
    private fun JsonObject.optionalString(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.requiredString(key: String) = optionalString(key)?.takeIf { it.isNotBlank() } ?: invalid()
    private fun invalid(): Nothing = throw BeijingQueryException(BeijingFailureKind.INVALID_RESPONSE)
}
