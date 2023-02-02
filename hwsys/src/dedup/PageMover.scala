package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.fsm.StateMachine

import coyote._
import util.Helpers._

class PageMoverIO(axiConf: Axi4Config) extends Bundle {
  // ctrl
  val start = in Bool()
  val hostAddr = in UInt(64 bits)
  val len = in UInt(16 bits) // set 16 bits to avoid wide adder
  val cnt = in UInt(64 bits)
  val pid = in UInt(6 bits)
  val cntDone = out(Reg(UInt(64 bits))).init(0)

  // host data IO
  val hostd = new HostDataIO

  // page stream
  val pgStrm = master Stream(Bits(512 bits))

  def regMap(r: AxiLite4SlaveFactory, baseR: Int): Int = {
    implicit val baseReg = baseR
    start := r.createReadAndClearOnSet(start, (baseReg + 0) << log2Up(r.busDataWidth/8), 0)
    r.rwInPort(hostAddr, r.getAddr(1), 0, "PageMover: hostAddr")
    r.rwInPort(len,      r.getAddr(2), 0, "PageMover: len")
    r.rwInPort(cnt,      r.getAddr(3), 0, "PageMover: cnt")
    r.rwInPort(pid,      r.getAddr(4), 0, "PageMover: pid")
    r.read(cntDone,      r.getAddr(5), 0, "PageMover: cntDone")
    val assignOffs = 6
    assignOffs
  }
}

class PageMover() extends Component {

  val io = new PageMoverIO(Axi4ConfigAlveo.u55cHBM)

  val cntHostReq = Reg(UInt(64 bits)).init(0)
  val reqHostAddr = Reg(UInt(64 bits)).init(0)

  // req assignment
  val bpss_rd_req = ReqT()
  List(bpss_rd_req).foreach { e =>
    e.vaddr := reqHostAddr.resized
    e.len := io.len.resized
    e.stream := False
    e.sync := False
    e.ctl := True
    e.host := True
    e.dest := 0
    e.pid := io.pid
    e.vfid := 0
    e.rsrvd := 0
  }
  io.hostd.bpss_rd_req.data.assignFromBits(bpss_rd_req.asBits)
  io.hostd.bpss_wr_req.tieOff(false)
  io.hostd.bpss_rd_done.freeRun()
  io.hostd.bpss_wr_done.freeRun()
  io.hostd.axis_host_src.setIdle()

  when(io.hostd.bpss_rd_done.fire)(io.cntDone := io.cntDone + 1)

  io.pgStrm.translateFrom(io.hostd.axis_host_sink) (_ := _.tdata)

  when(io.hostd.bpss_rd_req.fire) {
    cntHostReq := cntHostReq + 1
    reqHostAddr := reqHostAddr + io.len
  }


  val fsm = new StateMachine {
    val IDLE = new State with EntryPoint
    val RD= new State

    IDLE.whenIsActive {
      when(io.start) (goto(RD))
    }

    IDLE.onExit {
      List(cntHostReq, io.cntDone).foreach(_.clearAll())
    }

    // host read
    RD.whenIsActive {
      when(io.cntDone === io.cnt) (goto(IDLE))
    }
  }

  io.hostd.bpss_rd_req.valid := (cntHostReq =/= io.cnt) && fsm.isActive(fsm.RD)
}