package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._

import util.RenameIO
import util.Helpers._
import coyote.HostDataIO

class WrapDedupSys() extends Component with RenameIO {

  val io = new Bundle {
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    val hostd = new HostDataIO
    val axi_mem = master(Axi4(Axi4ConfigAlveo.u55cHBM))
  }

  val dedupCore = new WrapDedupCore()
  val pgMover = new PageMover()

  /** pipeline the axi_mem & pgStrmIn for SLR crossing
   * dedupCore is arranged to SLR1, pgMover and other logic are in SLR0 */
  dedupCore.io.pgStrmIn << pgMover.io.pgStrm.pipelined(StreamPipe.FULL)
  io.axi_mem << dedupCore.io.axiMem.pipelined(StreamPipe.FULL)
  pgMover.io.hostd.connectAllByName(io.hostd)

  val ctrlR = new AxiLite4SlaveFactory(io.axi_ctrl, useWriteStrobes = true)
  val ctrlRByteSize = ctrlR.busDataWidth/8
  //REG: initEn (clearOnSet, addr: 0, bit: 0)
  dedupCore.io.initEn := ctrlR.createReadAndClearOnSet(Bool(), 0 << log2Up(ctrlRByteSize), 0)
  //REG: initDone (readOnly, addr: 1, bit: 0)
  ctrlR.read(dedupCore.io.initDone, 1 << log2Up(ctrlRByteSize), 0)

  //Resp logic
  val pgRespQ = StreamFifo(dedupCore.io.pgResp.payload, 512)
  pgRespQ.io.push << dedupCore.io.pgResp

  val pgRespPad = Stream(Bits(128 bits))
  pgRespPad.translateFrom(pgRespQ.io.pop)((a,b) => {
    a := b.pgPtr.resize(64) ## (b.pgIdx ## b.isExist).resize(64)
  })
  //REG: pgResp (addr: 2-3 [pgPtr :: padding :: pgIdx :: isExist])
  ctrlR.readStreamNonBlocking(pgRespPad, 2 << log2Up(ctrlRByteSize))
  //REG: pgMover control (addr: 4-9)
  pgMover.io.regMap(ctrlR, 4)

}
