package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

import spinal.lib.bus.amba4.axilite._
import coyote.HostDataIO

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import util.sim._
import util.sim.SimDriver._
import util.AxiMux

import scala.collection.mutable
import scala.collection.mutable._
import scala.util.Random

class DedupSysFuncTests extends AnyFunSuite {
  def dedupSysFuncSim(): Unit = {

    val compiledRTL = if (sys.env.contains("VCS_HOME")) SimConfig.withVpdWave.withVCS.compile(new WrapDedupSysTB())
    else SimConfig.withWave.compile(new WrapDedupSysTB())

    compiledRTL.doSim { dut =>
      DedupSysFuncSim.doSim(dut)
    }
  }

  test("Sys Func Test: Core with no BF and dummy allocator"){
    dedupSysFuncSim()
  }
}


object DedupSysFuncSim {

  def doSim(dut: WrapDedupSysTB, verbose: Boolean = false): Unit = {
    dut.clockDomain.forkStimulus(period = 2)
    SimTimeout(100000)
    /** init */
    initAxi4LiteBus(dut.io.axi_ctrl)
    dut.io.hostd.axis_host_sink.valid #= false
    dut.io.hostd.bpss_wr_done.valid   #= false
    dut.io.hostd.bpss_rd_done.valid   #= false
    dut.io.hostd.axis_host_src.ready  #= false
    dut.io.hostd.bpss_wr_req.ready    #= false
    dut.io.hostd.bpss_rd_req.ready    #= false

    /** memory model for HashTab */
    SimDriver.instAxiMemSim(dut.io.axi_mem, dut.clockDomain, None)
    dut.clockDomain.waitSampling(10)

    /** Stage 1: insert pages and get SHA3*/
    val pageNum = 256
    val dupFacotr = 2
    val opNum = 4
    assert(pageNum%dupFacotr==0, "pageNumber must be a multiple of dupFactor")
    assert(pageNum%opNum==0, "pageNumber must be a multiple of operation number")
    val uniquePageNum = pageNum/dupFacotr
    val pagePerOp = pageNum/opNum
    val pageSize = 4096
    val bytePerWord = 64

    // random data
    // val uniquePgData = List.fill[BigInt](uniquePageNum*pageSize/bytePerWord)(BigInt(bytePerWord*8, Random))
    // fill all word with page index
    val uniquePgData = List.tabulate[BigInt](uniquePageNum*pageSize/bytePerWord){idx => idx/(pageSize/bytePerWord)}
    
    val opStrmData: ListBuffer[BigInt] = ListBuffer()
    for (i <- 0 until opNum) {
      val newInstr = SimInstrHelpers.writeInstrGen(2*i*pagePerOp,pagePerOp)
      opStrmData.append(newInstr)
    }

    var pgStrmData: ListBuffer[BigInt] = ListBuffer()

    // 1,...,N, 1,...,N
    for (j <- 0 until dupFacotr) {
      for (i <- 0 until uniquePageNum) {
        for (k <- 0 until pageSize/bytePerWord) {
          pgStrmData.append(uniquePgData(i*pageSize/bytePerWord+k))
        }
      }
    }

    val goldenPgIsNew    = List.tabulate[BigInt](pageNum){idx => if (idx < uniquePageNum) 1 else 0}
    val goldenpgRefCount = List.tabulate[BigInt](pageNum){idx => (idx/(uniquePageNum)) + 1}
    val goldenPgIdx      = List.tabulate[BigInt](pageNum){idx => ((idx/pagePerOp)) * pagePerOp + idx} // start + idx

    // random shuffle
    // var initialPgOrder: List[Int] = List()
    // for (j <- 0 until dupFacotr) {
    //   initialPgOrder = initialPgOrder ++ List.range(0, uniquePageNum) // page order: 1,2,...,N,1,2,...,N,...
    // }

    // val shuffledPgOrder = Random.shuffle(initialPgOrder)
    // assert(shuffledPgOrder.length == pageNum, "Total page number must be the same as the predefined parameter")
    // for (pgIdx <- 0 until pageNum) {
    //   val uniquePgIdx = shuffledPgOrder(pgIdx)
    //   for (k <- 0 until pageSize/bytePerWord) {
    //     pgStrmData.append(uniquePgData(uniquePgIdx * pageSize/bytePerWord+k))
    //   }
    // }

    // merge page stream and operation stream
    val inputBufferUsefulLen = pageNum * pageSize / bytePerWord + opNum * 1
    // round up to 64 word
    val inputBufferTotalLen = if (inputBufferUsefulLen % 64 == 0) {inputBufferUsefulLen} else {(inputBufferUsefulLen/64 + 1) *64}
    val inputBufferPaddingLen = inputBufferTotalLen - inputBufferUsefulLen

    val dedupSysInputData: mutable.Queue[BigInt] = mutable.Queue()
    var pgStart = 0
    for (opIdx <- 0 until opStrmData.length){
      val currentInstr = opStrmData(opIdx)
      val opCode = SimHelpers.bigIntTruncVal(currentInstr, 511, 510)
      dedupSysInputData.enqueue(currentInstr)
      if (opCode == 1){
        // write, get data
        for(pgIdx <- pgStart until (pgStart + pagePerOp)){
          for (k <- 0 until pageSize / bytePerWord) {
            dedupSysInputData.enqueue(pgStrmData(pgIdx * pageSize / bytePerWord + k))
          }
        }
        pgStart = pgStart + pagePerOp
      }
    }
    for (padIdx <- 0 until inputBufferPaddingLen){
      dedupSysInputData.enqueue(SimInstrHelpers.nopGen())
    }
    
    val pgRespQ: mutable.Queue[BigInt] = mutable.Queue()
    println(s"Total input buffer size: ${dedupSysInputData.size}")
    // host model
    hostModel(dut.clockDomain, dut.io.hostd, dedupSysInputData, pgRespQ)

    /** AXIL Control Reg */
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 0, 1) // INITEN

    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 3<<3, 0) // RDHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 4<<3, 0x1000) // WRHOSTADDR
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 5<<3, 4096) // LEN
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 6<<3, inputBufferTotalLen/64) // CNT = pageNum
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 7<<3, 0) // PID
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 10<<3, 8) // factorThrou

    // confirm initDone
    while(readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 1<<3) == 0) {
      dut.clockDomain.waitSampling(10)
    }
    // set start
    setAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 2<<3, 1)

    while(dedupSysInputData.nonEmpty) {
      dut.clockDomain.waitSampling(10)
    }
    // wait for tail pages processing
    while (readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9 << 3) < pageNum/16) {
      dut.clockDomain.waitSampling(100)
    }

    val rdDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 8<<3)
    val wrDone = readAxi4LiteReg(dut.clockDomain, dut.io.axi_ctrl, 9<<3)
    println(s"rdDone: $rdDone, wrDone: $wrDone")

    // parse the pgRespQ
    val pgRespData: mutable.Queue[BigInt] = mutable.Queue()

    var respIdx = 0
    while(pgRespQ.nonEmpty) {
      val respData     = pgRespQ.dequeue()
      val SHA3Hash     = SimHelpers.bigIntTruncVal(respData, 255, 0)
      val RefCount     = SimHelpers.bigIntTruncVal(respData, 287, 256)
      val SSDLBA       = SimHelpers.bigIntTruncVal(respData, 319, 288)
      val hostLBAStart = SimHelpers.bigIntTruncVal(respData, 351, 320)
      val hostLBALen   = SimHelpers.bigIntTruncVal(respData, 383, 352)
      val isExec       = SimHelpers.bigIntTruncVal(respData, 509, 509)
      val opCode       = SimHelpers.bigIntTruncVal(respData, 511, 510)
      
      assert(RefCount == goldenpgRefCount(respIdx))
      assert(hostLBAStart == goldenPgIdx(respIdx))
      assert(hostLBALen == 1)
      assert(isExec == goldenPgIsNew(respIdx))
      assert(opCode == 1)

      // println(s"pageIdx: ${respIdx}")
      // println(s"${RefCount} == ${goldenpgRefCount(respIdx)}")
      // println(s"${hostLBAStart} == ${goldenPgIdx(respIdx)}")
      // println(s"${hostLBALen} == 1")
      // println(s"${isExec} == ${goldenPgIsNew(respIdx)}")
      // println(s"${opCode} == 0")
      respIdx = respIdx + 1
    }
  }
}

case class WrapDedupSysTB() extends Component{

  val conf = DedupConfig()

  val io = new Bundle {
    val axi_ctrl = slave(AxiLite4(AxiLite4Config(64, 64)))
    val hostd = new HostDataIO
    val axi_mem = master(Axi4(Axi4ConfigAlveo.u55cHBM))
  }

  val dedupSys = new WrapDedupSys()

  // auto connect
  dedupSys.io.axi_ctrl <> io.axi_ctrl
  dedupSys.io.hostd    <> io.hostd

  // axi mux, RR arbitration
  val axiMux = AxiMux(conf.htConf.sizeFSMArray)
  for (idx <- 0 until conf.htConf.sizeFSMArray){
    axiMux.io.axiIn(idx) << dedupSys.io.axi_mem(idx)
  }
  axiMux.io.axiOut >> io.axi_mem
}