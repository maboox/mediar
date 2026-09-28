package com.mediar.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediar.app.logic.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StoreTest {
    @Test fun duplicateCorrectionAndInventory() {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Store(ctx);val id=db.addMedicine("test-${UUID.randomUUID()}","","tablet",10.0,2.0)
        val plan=Plan(UUID.randomUUID().toString(),id,LocalDate.now(),null,"daily",emptySet(),1,1,0,false,listOf(SlotSpec("a","08:00",.5)))
        db.savePlan(plan);val slot=db.materialize(LocalDate.now(),0).first{it.planId==plan.id}
        assertTrue(db.confirm(id,slot.id,.5,System.currentTimeMillis(),"NFC").success)
        assertFalse(db.confirm(id,slot.id,.5,System.currentTimeMillis(),"NFC").success)
        assertEquals(9.5,db.stock(id),.0001)
        val dose=db.doseFor(slot.id)!!;db.correct(dose.id,"mistake")
        assertEquals(10.0,db.stock(id),.0001)
        assertEquals("unknown",db.status(slot.id))
        db.close()
    }
}
