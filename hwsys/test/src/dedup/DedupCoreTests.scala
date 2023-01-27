package dedup

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._

import util.sim._

class CoreTests extends AnyFunSuite {
  def dedupCoreSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupCore())
    else SimConfig.withWave.compile(new WrapDedupCore())

    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      DedupCoreSim.doSim(dut)
    }
  }

  test("DedupCoreTest"){
    dedupCoreSim()
  }
}

object DedupCoreSim {
  def doSim(dut: WrapDedupCore, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 10)
    SimTimeout(10000)
    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axiMem, dut.clockDomain, None)

    /** init */
    dut.io.initEn #= true
    dut.clockDomain.waitSampling()
    dut.io.initEn #= false
    dut.clockDomain.waitSamplingWhere(dut.io.initDone.toBoolean)

    /** generate page stream */
    

  }
}