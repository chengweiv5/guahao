package cn.guahao.core.psc

import cn.guahao.core.*
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

val pscJson = Json { ignoreUnknownKeys = true }
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull
    ?: throw HospitalException("医院响应缺少必要字段，请重新接入或在官方页面核对")
fun JsonObject.optional(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun responseObject(raw: String): JsonObject = try { pscJson.parseToJsonElement(raw).jsonObject }
    catch (_: Exception) { throw HospitalException("医院响应结构变化，请在官方页面核对") }

object PscPageParser {
    /** Only strict JSON assignments in script bodies are accepted. No JavaScript is evaluated. */
    fun variable(html: String, name: String): JsonElement {
        val values = mutableListOf<JsonElement>()
        val declaration = Regex("(?:^|[;\\n\\r])\\s*(?:var|let|const)\\s+${Regex.escape(name)}\\s*=\\s*")
        for (script in Regex("<script\\b[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html)) {
            val body = script.groupValues[1]
            for (assignment in declaration.findAll(body)) {
                val start = assignment.range.last + 1
                if (start >= body.length || body[start] !in "[{") throw HospitalException("医院页面数据格式变化")
                var string = false; var escape = false; var depth = 0; var end = -1
                for (i in start until body.length) {
                    val c = body[i]
                    if (string) {
                        if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') string = false
                    } else when(c) {
                        '"' -> string = true
                        '[', '{' -> depth++
                        ']', '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
                    }
                }
                if (end < 0) throw HospitalException("医院页面数据不完整")
                values += try { pscJson.parseToJsonElement(body.substring(start, end)) }
                    catch (_: Exception) { throw HospitalException("医院页面数据不再是标准 JSON") }
            }
        }
        return values.singleOrNull() ?: throw HospitalException("未取得唯一医院页面数据，请重新接入")
    }
    fun departments(html: String): List<DepartmentRef> {
        val groups = variable(html, "deptOneList").jsonArray.map { it.jsonObject }.associate { it.text("code") to it.text("name") }
        val mappings = variable(html, "deptTwoMap").jsonObject
        return mappings.flatMap { (parent, values) ->
            values.jsonArray.map { raw -> val d = raw.jsonObject
                require(d.text("pcode") == parent)
                DepartmentRef(parent, d.text("code"), groups[parent] ?: throw HospitalException("科室关联缺失"), d.text("name"), d.optional("oriDeptTwo") ?: "")
            }
        }.distinct()
    }
    data class Day(val date: LocalDate, val availability: Int, val candidates: List<Candidate>)
    fun schedule(html: String, department: DepartmentRef): List<Day> =
        variable(html, "regisInfo").jsonObject.getValue("dayViews").jsonArray.map { item ->
            val day = item.jsonObject
            val date = parseDate(day.text("day"))
            // The official page returns JSON null for dates with no published schedules.
            val registries = when (val value = day["registryList"]) {
                JsonNull -> emptyList()
                is JsonArray -> value
                else -> throw HospitalException("医院排班列表格式变化，请稍后刷新或在官方页面核对")
            }
            val candidates = registries.flatMap { registry ->
                val r = registry.jsonObject
                val count = r.text("count").toInt().also { require(it >= 0) }
                r.getValue("regHourList").jsonArray.map { itemHour ->
                    val hour = itemHour.jsonArray; require(hour.size == 2)
                    val period = hour[0].jsonPrimitive.content
                    val range = timeRange(period)
                    Candidate(department, r.text("doctor_code"), r.text("doctor"), date,
                        r.text("reg_half"), period, range.first, range.second, yuanToFen(r.text("fee")),
                        minOf(count, hour[1].jsonPrimitive.content.toInt().also { require(it >= 0) }), r.text("title_type"), r.text("iscanceled") != "0")
                }
            }
            Day(date, day.text("syqty").toInt(), candidates)
        }
}
fun parseDate(value: String): LocalDate = when {
    Regex("[0-9]{8}").matches(value) -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
    Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value) -> LocalDate.parse(value)
    else -> throw HospitalException("医院日期格式变化，请在官方页面核对")
}
fun timeRange(value: String): Pair<Int, Int> {
    val m = Regex("([0-2][0-9]):([0-5][0-9])-([0-2][0-9]):([0-5][0-9])").matchEntire(value)
        ?: throw HospitalException("医院时段格式变化，请在官方页面核对")
    val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    val end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
    require(start in 0..1439 && end in 1..1440 && start < end)
    return start to end
}
fun parseOrders(root: JsonObject, patient: PatientRef): List<OrderSnapshot> {
    if (root.text("code") != "200") throw HospitalException("医院订单查询未成功，请重新接入或稍后刷新")
    return root.getValue("data").jsonArray.map { raw ->
        val o = raw.jsonObject
        val status = o.text("status")
        val phase = when(status) { "1" -> OrderPhase.LOCKED; "2" -> OrderPhase.BOOKED; "7" -> OrderPhase.INSURANCE_PENDING; "6" -> OrderPhase.INSURANCE_PAID; else -> OrderPhase.OTHER }
        val half = when(o.text("ampm")) { "上午", "1" -> "1"; "下午", "2" -> "2"; else -> throw HospitalException("医院半日格式变化") }
        val hour = o.text("reserved_date"); timeRange(hour)
        val invalid = o.optional("invalidtime")?.takeIf { it.isNotBlank() }?.let {
            try { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant() }
            catch (_: Exception) { throw HospitalException("医院付款期限格式变化，请到官方页面核对") }
        }
        OrderSnapshot(o.text("orderno"), patient.copy(patientId = o.text("ptno")), o.optional("doctor_code"), o.optional("dept_code"),
            o.text("doctor"), o.text("dept"), o.text("source"), parseDate(o.text("actdate")), half, hour,
            yuanToFen(o.text("fee")), phase, status, invalid,
            o.optional("iscanceled") !in setOf(null, "0") || o.optional("isvolblooddonation") !in setOf(null, "0", "") ||
                !o.optional("afterlocktxt").isNullOrBlank())
    }
}
fun decodeLockCode(code: String): LockReply = when(code) {
    "0" -> LockReply.Accepted; "2", "3" -> LockReply.NoStock; else -> LockReply.Rejected(code)
}
fun decodeAsync(root: JsonObject): AsyncReply = when(val code = root.text("code")) {
    "2" -> AsyncReply.Pending
    "2011" -> AsyncReply.NoStock
    "0" -> {
        val data = root["data"] as? JsonArray
        fun value(i: Int) = (data?.getOrNull(i) as? JsonPrimitive)?.contentOrNull
        val payment = if (data != null && data.size >= 10 && listOf(5,6,7,9).all { value(it) != null })
            PaymentContext(value(5) == "1", value(6) == "1", value(7) != "0" || value(9) != "0") else null
        value(2)?.takeIf { it.isNotBlank() }?.let { AsyncReply.OrderFound(it, payment) } ?: AsyncReply.Unknown("missing-order")
    }
    else -> AsyncReply.Unknown(code)
}
