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
    val axi_mem_0 = master(Axi4(Axi4ConfigAlveo.u55cHBM))
  }

  val dedupCore = new WrapDedupCore()
  val hostIntf = new HostIntf()

  /** pipeline the axi_mem & pgStrmIn for SLR crossing
   * dedupCore is arranged to SLR1, pgMover and other logic are in SLR0 */
  dedupCore.io.pgStrmIn << hostIntf.io.pgStrm.pipelined(StreamPipe.FULL)
  io.axi_mem_0 << dedupCore.io.axiMem.pipelined(StreamPipe.FULL)
  hostIntf.io.hostd.connectAllByName(io.hostd)

  val ctrlR = new AxiLite4SlaveFactory(io.axi_ctrl, useWriteStrobes = true)
  val ctrlRByteSize = ctrlR.busDataWidth/8
  //REG: initEn (write only, addr: 0, bit: 0, auto-reset)
  val rInit = ctrlR.createWriteOnly(Bool(), 0 << log2Up(ctrlRByteSize), 0)
  rInit.clearWhen(rInit)
  dedupCore.io.initEn := rInit
  //REG: initDone (readOnly, addr: 1, bit: 0)
  ctrlR.read(dedupCore.io.initDone, 1 << log2Up(ctrlRByteSize), 0)
  //REG: host interface (addr: 2-8)
  val ctrlRNumHostIntf = hostIntf.io.regMap(ctrlR, 2)

  //Resp logic
  val pgRespPad = Stream(Bits(128 bits))
  pgRespPad.translateFrom(dedupCore.io.pgResp)((a,b) => {
    a := b.pgPtr.resize(64) ## (b.pgIdx ## b.isExist).resize(64)
  })

  /** SLR0 << SLR1 */
  hostIntf.io.pgResp << pgRespPad.pipelined(StreamPipe.FULL)

  /** pgStore throughput control [0:15] */
  dedupCore.io.factorThrou := ctrlR.createReadAndWrite(UInt(5 bits), (ctrlRNumHostIntf+2) << log2Up(ctrlRByteSize), 0)

}
