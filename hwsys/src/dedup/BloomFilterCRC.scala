package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.crypto.checksum._

import scala.collection.mutable.ArrayBuffer

case class BloomFilterConfig(m: Int, k: Int, dataWidth: Int = 32) {
  assert(m >= 8, "m MUST >= 8")
  assert(m == (1 << log2Up(m)), "m MUST be a power of 2")
  val mWidth    = log2Up(m)
  val bramWidth = 8
  val bramDepth = m / bramWidth
}

case class BloomFilterIO(conf: BloomFilterConfig) extends Bundle {
  val initEn   = in Bool () // trigger zeroing SRAM content
  val initDone = out Bool ()
  val frgmIn   = slave Stream (Fragment(Bits(conf.dataWidth bits)))
  val res      = master Stream (Bool())
  val memRdData = out Bits(conf.bramWidth bits)
  val memWrData = out Bits(conf.bramWidth bits)
  val memRdAddr = out UInt(log2Up(conf.bramDepth) bits)
  val memWrAddr = out UInt(log2Up(conf.bramDepth) bits)
  val memWrBackEn = out Bool()
}

class BloomFilterCRC() extends Component {
  // val bfConf = BloomFilterConfig(1 << log2Up(6432424), 3) // optimal value with 4096B page size, 10GB, 50% occupacy
  val bfConf = BloomFilterConfig(1 << log2Up(16384), 3) // simulation test only, avoid long init time
  val io     = BloomFilterIO(bfConf)

  val mem        = Mem(Bits(bfConf.bramWidth bits), wordCount = bfConf.bramDepth)
  val memWrEn    = False
  val cntMemInit = Counter(log2Up(bfConf.bramDepth) bits, memWrEn)

  val crcSchemes = List(
    CRC32.Standard,
    CRC32.Bzip2,
    CRC32.Jamcrc,
    CRC32.C,
    CRC32.D,
    CRC32.Mpeg2,
    CRC32.Posix,
    CRC32.Xfer
  )

  val crcKernel = ArrayBuffer.empty[CRCModule]
  for (i <- 0 until bfConf.k)
    crcKernel += CRCModule(CRCCombinationalConfig(crcSchemes(i), bfConf.dataWidth bits))

  io.frgmIn.setBlocked()
  crcKernel.foreach(_.io.cmd.setIdle())

  io.res.setIdle()

  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val fsm = new StateMachine {
    val IDLE                                      = new State with EntryPoint
    val INIT, RUN_CRCINIT, RUN_NORMAL, RUN_LOOKUP, RUN_RETURN = new State

    val lookUpEn, wrBackEn = False

    val cntLookup     = Counter(bfConf.k, isActive(RUN_LOOKUP) & lookUpEn)
    val cntWrBack     = Counter(bfConf.k, isActive(RUN_LOOKUP) & wrBackEn)
    val lookupIsExist = Reg(Bool())

    IDLE.whenIsActive {
      when(io.initEn)(goto(INIT))
    }

    INIT.whenIsActive {
      rInitDone := False
      memWrEn   := True
      mem.write(cntMemInit, B(0, bfConf.bramWidth bits), memWrEn)
      when(cntMemInit.willOverflow) {
        rInitDone := True
        goto(RUN_CRCINIT)
      }
    }

    RUN_CRCINIT.whenIsActive {
      // init logic
      crcKernel.foreach { x =>
        x.io.cmd.valid := True
        x.io.cmd.data  := 0
        x.io.cmd.mode  := CRCCombinationalCmdMode.INIT
      }
      when(io.initEn)(goto(INIT)) otherwise (goto(RUN_NORMAL))
    }

    RUN_NORMAL.whenIsActive {
      // logic
      io.frgmIn.ready := True
      crcKernel.foreach { x =>
        x.io.cmd.valid := io.frgmIn.valid
        x.io.cmd.data  := io.frgmIn.fragment
        x.io.cmd.mode  := CRCCombinationalCmdMode.UPDATE
      }
      when(io.initEn)(goto(INIT)) otherwise {
        when(io.frgmIn.isLast & io.frgmIn.fire)(goto(RUN_LOOKUP))
      }
    }

    RUN_LOOKUP.onEntry {
      lookupIsExist := True
    }

    RUN_LOOKUP.whenIsActive {

      val memRdAddr  = UInt(log2Up(bfConf.bramDepth) bits)
      val memWrAddr  = RegNext(memRdAddr) // one clock rd latency
      val rBitRdAddr = Reg(UInt(log2Up(bfConf.bramWidth) bits)).init(0)

      val cntLookupIsOverflow = cntLookup.value === cntLookup.start && RegNext(lookUpEn)
      lookUpEn := ~cntLookupIsOverflow
      val lookUpVld = RegNext(lookUpEn) // one clock rd latency
      wrBackEn := lookUpVld

      val crcResVec = Vec(crcKernel.map(_.io.crc))

      memRdAddr  := crcResVec(cntLookup)(bfConf.mWidth - 1 downto log2Up(bfConf.bramWidth)).asUInt
      rBitRdAddr := crcResVec(cntLookup)(log2Up(bfConf.bramWidth) - 1 downto 0).asUInt

      val memRdData = mem.readSync(memRdAddr, lookUpEn)
      memRdData.dontSimplifyIt()

      when(lookUpVld) {
        lookupIsExist := lookupIsExist & memRdData(rBitRdAddr)
      }

      val memWrData = memRdData | (1 << rBitRdAddr) // bitwise
      mem.write(memWrAddr, memWrData, wrBackEn)

      when(io.initEn)(goto(INIT)) otherwise {
        when(cntWrBack.willOverflow) (goto(RUN_RETURN))
      }

      io.memRdAddr := memRdAddr
      io.memWrAddr := memWrAddr
      io.memRdData := memRdData
      io.memWrData := memWrData
      io.memWrBackEn := wrBackEn

    }

    io.memRdAddr := 0
    io.memWrAddr := 0
    io.memRdData := 0
    io.memWrData := 0
    io.memWrBackEn := False


    RUN_RETURN.whenIsActive {
      io.res.payload := lookupIsExist
      io.res.valid := True
      when(io.initEn)(goto(INIT)) otherwise {
        when(io.res.fire) (goto(RUN_CRCINIT))
      }
    }
  }

}
