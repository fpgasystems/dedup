package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.checksum._
import spinal.crypto._

import scala.collection.mutable.ArrayBuffer

case class BloomFilterConfig (m: Int, k: Int, dataWidth: Int = 32) {
  assert(m>=8, "m MUST >= 8")
  assert(m==(1<<log2Up(m)), "m MUST be a power of 2")
  val mWidth = log2Up(m)
  val bramWidth = 8
  val bramDepth = m/bramWidth
}

case class BloomFilterIO(conf: BloomFilterConfig) {
  val init = in Bool() //trigger zeroing SRAM content
  val frgmIn = slave Stream(Fragment(Bits(conf.dataWidth bits)))
  val res = master Flow(Bool())
}

class BloomFilterCRC() extends Component {
  val bfConf = BloomFilterConfig(1<<log2Up(6432424), 3) // optimal value with 4096B page size, 10GB, 50% occupacy
  val io = BloomFilterIO(bfConf)

  val mem = Mem(Bits(bfConf.bramWidth bits), wordCount = bfConf.bramDepth)
  val memWrEn = False
  val cntMemInit = Counter(log2Up(bfConf.bramDepth) bits, memWrEn)

  val crcSchemes = List(
    CRC32.Standard,
    CRC32.Bzip2 ,
    CRC32.Jamcrc,
    CRC32.C,
    CRC32.D,
    CRC32.Mpeg2,
    CRC32.Posix,
    CRC32.Xfer
  )

//  var crcKernel = Array.fill(bfConf.k)(CRCModule(CRCCombinationalConfig(crcSchemes(0), bfConf.dataWidth bits)))
  val crcKernel = ArrayBuffer.empty[CRCModule]
  for (i <- 0 until bfConf.k){
    crcKernel += CRCModule(CRCCombinationalConfig(crcSchemes(i), bfConf.dataWidth bits))
  }

  val bfFsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val INIT, RUN_CRCINIT, RUN_NORMAL = new State

    io.frgmIn.setBlocked()
    for (i <- 0 until bfConf.k) {
      crcKernel(i).io.cmd.setIdle()
    }
    io.res.setIdle()

    IDLE.whenIsActive {
      when(io.init) (goto(INIT))
    }

    INIT.whenIsActive {
      memWrEn := True
      mem.write(cntMemInit, B(0, bfConf.bramWidth bits), memWrEn)
      when(cntMemInit.willOverflow)(goto(RUN_CRCINIT))
    }

    RUN_CRCINIT.whenIsActive {
      // init logic
      for (i <- 0 until bfConf.k) {
        crcKernel(i).io.cmd.valid := True
        crcKernel(i).io.cmd.data := 0
        crcKernel(i).io.cmd.mode := CRCCombinationalCmdMode.INIT
      }
      when(io.init) (goto(INIT)) otherwise(goto(RUN_NORMAL))
    }

    RUN_NORMAL.whenIsActive {
      // logic
      io.frgmIn.ready := True
      for (i <- 0 until bfConf.k) {
        crcKernel(i).io.cmd.valid := io.frgmIn.valid
        crcKernel(i).io.cmd.data := io.frgmIn.fragment
        crcKernel(i).io.cmd.mode := CRCCombinationalCmdMode.UPDATE
      }
      when(io.frgmIn.isLast & io.frgmIn.fire) (goto(RUN_CRCINIT))
    }
  }
}

