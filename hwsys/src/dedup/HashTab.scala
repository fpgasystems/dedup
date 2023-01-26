package dedup

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._

object HashTabVerb extends SpinalEnum {
  val INSERT, LOOKUP = newElement()
}

case class HashTabCmd (conf: HashTabConfig) extends Bundle {
  val verb = HashTabVerb()
  val hashVal = Bits(conf.hashValWidth bits)
  val isPostInst = Bool()
}

case class HashTabResp (ptrWidth: Int = 64) extends Bundle {
  val isExist = Bool()
  val dupPtr = UInt(ptrWidth bits) // if(isExist) -> retur the ptr value found in the HashTable
}

case class DRAMRdCmd(conf: HashTabConfig) extends Bundle {
  val memOffs = UInt(conf.ptrWidth bits)
  val nEntry = UInt(16 bits)
}

case class DRAMWrCmd(conf: HashTabConfig) extends Bundle {
  val memOffs = UInt(48 bits) // hash entry offset in DRAM
  val ptrVal = UInt(conf.ptrWidth bits) // page pointer in storage
  val hashVal = Bits(conf.hashValWidth bits)
}

case class HashTabConfig (hashValWidth: Int = 256, ptrWidth: Int = 64, hashTabSize: Int = (1<<20), bucketSize: Int = 256) {
  assert(hashTabSize%bucketSize==0, "Hash table size (#entry) should be a multiple of bucketSize")
  val nBucket = hashTabSize / bucketSize
  val idxBucketWidth = log2Up(nBucket)
  val bucketOffsWidth = log2Up(bucketSize)
  val entryType = Bits(512 bits)
  val entryByteSize = entryType.getBitsWidth/8
  val entryAddrBitShift = log2Up(entryByteSize)

  val hashTabOffset = 0 // global memory offset of the hash table
  val bucketByteSize = bucketSize * entryType.getBitsWidth/8
  val bucketAddrBitShift = log2Up(bucketByteSize)

  /** Hardware parameters (performance related) */
  val cmdQDepth = 4
}

case class HashTabIO(conf: HashTabConfig) extends Bundle {
  val initEn = in Bool()
  val initDone = out Bool()
  val cmd = slave Stream(HashTabCmd(conf))
  val ptrStrm1 = slave Stream(UInt(conf.ptrWidth bits))
  val ptrStrm2 = slave Stream(UInt(conf.ptrWidth bits))
  val res = master Stream(HashTabResp(conf.ptrWidth))
  /** DRAM interface */
  val axiConf = Axi4ConfigAlveo.u55cHBM
  val axiMem = master(Axi4(axiConf))
}

class HashTab () {

  val conf = HashTabConfig()

  val io = HashTabIO(conf)

  val cmdPostIns = Stream(HashTabCmd(conf))
  /** priority gives to cmdPostIns */
  val cmdMux = StreamMux(cmdPostIns.valid ? U(1) | U(0), Seq(io.cmd, cmdPostIns))

  /** default status of strems */
  io.cmd.setBlocked()
  io.ptrStrm1.setBlocked()
  io.ptrStrm2.setBlocked()

  cmdMux.setBlocked()

  /** SRAM store the valid elements in each bucket */
  val mem  = Mem(UInt(conf.bucketOffsWidth bits), wordCount = conf.nBucket)

  /** FSMs */


  val topFsm = new StateMachine {
    val IDLE, INIT = new State
    val RUN = new StateParallelFsm(lookupFsm())
    setEntry(IDLE)

    /** Resource */
    val rInitDone = RegInit(False)
    io.initDone := rInitDone
    val memWrEn = False
    val cntMemInit = Counter(conf.bucketOffsWidth bits, memWrEn)

    val memRdValid = RegNext(cmdMux.fire)
    val rCmdMuxVld = memRdValid
    val rCmdMux = RegNextWhen(cmdMux.payload, cmdMux.fire)

    val dramRdCmdQ = StreamFifo(DRAMRdCmd(conf), conf.cmdQDepth)
    val lookUpCmdQ = StreamFifo(HashTabCmd(conf), conf.cmdQDepth)
    val dramWrHashCmd = Stream(HashTabCmd(conf))
    val dramWrCmdQ = StreamFifo(DRAMWrCmd(conf), conf.cmdQDepth)

    /** Set default values */
    cmdPostIns.setIdle()

    IDLE.whenIsActive {
      when(io.initEn)(goto(INIT))
    }

    INIT.whenIsActive {
      rInitDone := False
      memWrEn := True
      mem.write(cntMemInit, B(0).resized, memWrEn)
      when(cntMemInit.willOverflow) {
        rInitDone := True
        goto(RUN)
      }
    }
    /** Normal mode */
    RUN.whenIsActive {
      when(io.initEn)(goto(INIT))
      /** should combine all following ready signals, also the needed join ptr1/2 should be valid */
      // FIXME: is correct to use dramWrCmdQ.io.push.ready?
      cmdMux.ready := (cmdMux.verb===HashTabVerb.LOOKUP) ? (dramRdCmdQ.io.push.ready & lookUpCmdQ.io.push.ready) | (cmdMux.isPostInst ? io.ptrStrm2.valid | io.ptrStrm1.valid) & dramWrCmdQ.io.push.ready
      val idxBucket = cmdMux.hashVal(conf.idxBucketWidth-1 downto 0).asUInt // lsb as the bucket index
      val rIdxBucket = RegNextWhen(idxBucket, cmdMux.fire)
      val rCmdMuxFire = RegNext(cmdMux.fire)

      /** logic after read latency (1 clk) */
      val bucketOccup = mem.readSync(idxBucket, cmdMux.fire) // entry occupancy in the target bucket
      mem.write(rIdxBucket, bucketOccup+1, rCmdMuxFire & (rCmdMux.verb===HashTabVerb.INSERT)) // increase the entry occup

      dramRdCmdQ.io.push.valid := (rCmdMux.verb===HashTabVerb.LOOKUP) & rCmdMuxVld
      dramRdCmdQ.io.push.payload.memOffs := conf.hashTabOffset + (rIdxBucket << conf.bucketAddrBitShift)
      dramRdCmdQ.io.push.payload.nEntry := bucketOccup

      lookUpCmdQ.io.push.valid := (rCmdMux.verb===HashTabVerb.LOOKUP) & rCmdMuxVld
      lookUpCmdQ.io.push.payload := rCmdMux

      dramWrHashCmd.valid := (rCmdMux.verb===HashTabVerb.INSERT) & rCmdMuxVld
      val dramWrJoin = StreamJoin(dramWrHashCmd, rCmdMux.isPostInst ? io.ptrStrm2 | io.ptrStrm1)

      dramWrCmdQ.io.push.translateFrom(dramWrJoin)((a, b) => {
        a.memOffs := conf.hashTabOffset + (rIdxBucket << conf.bucketAddrBitShift) + (bucketOccup << conf.entryAddrBitShift)
        a.ptrVal := b._2
        a.hashVal := b._1.hashVal
      })
    }

    def lookupFsm() = new StateMachine {

      /** Always block: Results comparison */
      io.axiMem.r.ready := True
      val isHashValMatch = io.axiMem.r.data(conf.hashValWidth-1 downto 0) === lookUpCmdQ.io.pop.hashVal
      val rDupPtr = RegNextWhen(io.axiMem.r.data(conf.ptrWidth+conf.hashValWidth-1 downto conf.hashValWidth), io.axiMem.r.fire & isHashValMatch)
      val rIsHashValMatch = RegInit(False)

      val burstLen = 8 //  outstanding words for 100ns round trip
      val cntAxiRdCmd = Counter(conf.bucketByteSize/(io.axiConf.dataWidth/8)/burstLen, io.axiMem.ar.fire)
      val cntAxiRdResp = Counter(conf.bucketByteSize/(io.axiConf.dataWidth/8)/burstLen, io.axiMem.r.fire & io.axiMem.r.last)
      val maxOutStandReq = 2
      val rDRAMRdCmd = RegNextWhen(dramRdCmdQ.io.pop.payload, dramRdCmdQ.io.pop.fire)

      io.axiMem.ar.addr := rDRAMRdCmd.memOffs + cntAxiRdCmd * burstLen * io.axiConf.dataWidth/8
      io.axiMem.ar.id := 0
      io.axiMem.ar.len := burstLen-1
      io.axiMem.ar.size := log2Up(io.axiConf.dataWidth/8)
      io.axiMem.ar.setBurstINCR()
      io.axiMem.ar.valid := False

      io.res.payload.isExist := rIsHashValMatch
      io.res.payload.dupPtr := rDupPtr.asUInt
      io.res.setIdle()

      val GET_CMD, ISSUE_AXI_CMD, RESP, POSTINST = new State
      setEntry(GET_CMD)

      GET_CMD.whenIsActive {
        /** Fire the read command */
        dramRdCmdQ.io.pop.ready := True
        when(dramRdCmdQ.io.pop.fire) (goto(ISSUE_AXI_CMD))
      }

      ISSUE_AXI_CMD.whenIsActive {
        when ((cntAxiRdCmd < (rDRAMRdCmd.nEntry / (burstLen*io.axiConf.dataWidth/8/conf.entryByteSize))) && ((cntAxiRdCmd - cntAxiRdResp) > maxOutStandReq)) {
          io.axiMem.ar.valid := True
        }
        when(isHashValMatch) {
          rIsHashValMatch.set()
          goto(RESP)
        }
        when(cntAxiRdCmd===cntAxiRdResp && cntAxiRdResp > 0) {
          goto(RESP)
        }
      }

      RESP.whenIsActive {
        io.res.valid := True
        when(io.res.fire){
          when(rIsHashValMatch) (goto(GET_CMD)) otherwise(goto(POSTINST))
        }
      }

      RESP.onExit {
        /** reset registers */
        lookUpCmdQ.io.pop.throwWhen(rIsHashValMatch)
        rIsHashValMatch.clear()
      }

      POSTINST.whenIsActive {
        /** insert (post lookup) logic */
        cmdPostIns.verb := HashTabVerb.INSERT
        cmdPostIns.hashVal := lookUpCmdQ.io.pop.hashVal
        cmdPostIns.isPostInst := True
        cmdPostIns.valid := True
        when(cmdPostIns.fire) (goto(GET_CMD))
        lookUpCmdQ.io.pop.throwWhen(cmdPostIns.fire)
      }

    }

    def insertFsm() = new StateMachine {
      /** dramWrCmdQ.io.pop -> axi */
      val wrCmdQFork = StreamFork2(dramWrCmdQ.io.pop, synchronous = false)
      io.axiMem.aw.translateFrom(wrCmdQFork._1)((a, b) => {
        a.addr := b.memOffs
        a.id := 0
        a.len := 0
        a.size := log2Up(io.axiConf.dataWidth/8)
        a.setBurstINCR()
      })
      io.axiMem.w.translateFrom(wrCmdQFork._2)((a,b) => {
        val d = (b.ptrVal ## b.hashVal)
        a.data := d.resized
        a.last := True
        a.strb := (1<<(d.getBitsWidth/8)-1)
      })
      io.axiMem.b.ready := True
    }

  }
}