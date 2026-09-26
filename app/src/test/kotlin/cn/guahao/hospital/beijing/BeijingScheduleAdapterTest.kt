package cn.guahao.hospital.beijing

import cn.guahao.core.*
import org.junit.Assert.*
import org.junit.Test

class BeijingScheduleAdapterTest {
    private val period = BeijingPeriod("time", "08:00-08:30", 480, 510, 3, false)
    private val slot = BeijingSlot("product", "1", "上午", 5000, 2, BeijingStock.AVAILABLE, "有号", listOf(period))
    private fun candidates(slot: BeijingSlot = this.slot) = BeijingDoctorSchedule(JtFixture.date,
        listOf(BeijingDoctor(DoctorRef("doctor", "测试医生"), listOf(slot))))
        .asDay(HospitalRoute("hospital-a", JtFixture.channel), JtFixture.department, DateAvailability.AVAILABLE).candidates

    @Test fun sourceAndBothProductKeysStayBoundToHospital() {
        val candidate = candidates().single()
        assertEquals(2, candidate.remaining)
        assertEquals(PlatformProduct(JtFixture.channel.id, "hospital-a", "product", "time"), candidate.platform)
        assertEquals(480, candidate.startMinute); assertEquals(510, candidate.endMinute)
    }
    @Test fun missingOrUncertainStockPriceAndTimeCannotBeSubmitted() {
        for (bad in listOf(slot.copy(feeFen = null), slot.copy(remaining = null), slot.copy(stock = BeijingStock.WAITLIST),
            slot.copy(stock = BeijingStock.UNKNOWN), slot.copy(periods = listOf(period.copy(generated = true))),
            slot.copy(periods = listOf(period.copy(remaining = null))), slot.copy(periods = listOf(period.copy(startMinute = null))),
            slot.copy(periods = listOf(period.copy(startMinute = 600))), slot.copy(productKey = ""))) assertTrue(candidates(bad).isEmpty())
    }
}
