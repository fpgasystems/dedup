package depdup

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.crypto.hash.BIG_endian
import spinal.crypto.hash.sim.HashIOsim
import spinal.core.sim._
import spinal.crypto.hash.sha3._
import ref.hash.SHA3

class Sha3Tests extends AnyFunSuite {

  val NBR_ITERATION = 100

  test("Test: SHA3"){
    val compiledRTL = SimConfig.withConfig(SpinalConfig(inlineRom = true)).compile(new SHA3Core_Std(SHA3_512))
    compiledRTL.doSim { dut =>
      dut.clockDomain.forkStimulus(2)
      HashIOsim.initializeIO(dut.io)
      dut.clockDomain.waitActiveEdge()
      for (i <- 0 to NBR_ITERATION) {
        HashIOsim.doSim(dut.io, dut.clockDomain, i, BIG_endian)(SHA3.digest(512))
      }
      dut.clockDomain.waitActiveEdge(5)
    }
  }
}