package dedup
package pagewriter

import spinal.core.Component.push
import spinal.core._
import spinal.lib._

import hashtable.HashTableLookupFSMRes
// import spinal.lib.fsm._
// import spinal.lib.bus.amba4.axi._
import util.{CntDynmicBound, FrgmDemux}

case class PageWriterConfig(dataWidth: Int = 512) {
  // Instr Decoder
  val readyQueueLogDepth = 4
  val waitingQueueLogDepth = 4
  val instrTagWidth = if (readyQueueLogDepth > waitingQueueLogDepth) (readyQueueLogDepth + 1) else (waitingQueueLogDepth + 1)

  // val pgIdxWidth = 32
  // val pgByteSize = 4096
  // val pgAddBitShift = log2Up(pgByteSize)
  // val pgWordCnt = pgByteSize / (dataWidth/8)

  // val pgIdxFifoSize = 256 // no fence on this stream flow, always assume there's enough space in idxFifo
  // val pgBufSize = 64 * pgWordCnt

  // val frgmType = Bits(dataWidth bits)
}

object SSDOp extends SpinalEnum(binarySequential) {
  /*write: write page and header
    erase: erase page and header
    read: read region(normal read)
    updateheader: only operate on header
  */
  val WRITE, ERASE, READ, UPDATEHEADER = newElement()
}

case class CombinedFullInstr (conf: DedupConfig) extends Bundle {
  // contain all info for Resp and SSD, and also tag for arbitration
  val SHA3Hash     = Bits(conf.htConf.hashValWidth bits)
  val RefCount     = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA       = UInt(conf.htConf.ptrWidth bits)
  val hostLBAStart = UInt(conf.htConf.ptrWidth bits)
  val hostLBALen   = UInt(conf.htConf.ptrWidth bits)
  val opCode       = DedupCoreOp()
  val tag          = UInt(conf.pwConf.instrTagWidth bits)
}

case class PageWriterResp (conf: DedupConfig) extends Bundle {
  val SHA3Hash     = Bits(conf.htConf.hashValWidth bits)
  val RefCount     = UInt(conf.htConf.ptrWidth bits)
  val SSDLBA       = UInt(conf.htConf.ptrWidth bits)
  val hostLBAStart = UInt(conf.htConf.ptrWidth bits)
  val hostLBALen   = UInt(conf.htConf.ptrWidth bits)
  // True means, new page(write exec), or GC (del exec), always True in read
  val isExec       = Bool() 
  val opCode       = DedupCoreOp()
}

case class SSDInstr (conf: DedupConfig) extends Bundle {
  // page header + operation, since header is small
  val SHA3Hash    = Bits(conf.htConf.hashValWidth bits)
  val RefCount    = UInt(conf.htConf.ptrWidth bits)
  val SSDLBAStart = UInt(conf.htConf.ptrWidth bits)
  // in dedup, write/erase LBALen = 1, read LBALen = input LBALen
  val SSDLBALen   = UInt(conf.htConf.ptrWidth bits)
  val opCode      = SSDOp()
}

/* the job of page writer is:
  1. buffer the input pages and wait for hash table lookup results
  2. based on the lookup results, do:
    1. decide (for insertion)write the page or not/(for deletion)delete the page or not
    2. update (or delete) page header
    3. send metadata/resp/read data back to host
*/
case class PageWriterSSIO(conf: DedupConfig) extends Bundle {
  val initEn       = in Bool ()
  val opStrmIn     = slave Stream (Bits(conf.instrTotalWidth bits))
  val pgStrmFrgmIn = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val lookupRes    = slave Stream (HashTableLookupFSMRes(conf.htConf))
  val res          = master Stream (PageWriterResp(conf))
  /** mock SSD interface */
  /* all instr and datain go to sink, and there will be no resp for write/read/erase
    3 ports: dataIn, instrIn, dataOut, header is packed in the instrIn(512-bit wide)
  */
  val SSDDataIn        = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDDataOut       = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  val SSDInstrIn       = master Stream (SSDInstr(conf))
  // val axiConf = Axi4ConfigAlveo.u55cHBM
  // val axiStore = master(Axi4(axiConf))
  /** bandwidth controller */
  val factorThrou = in UInt(5 bits)
}

class PageWriterSubSystem(conf: DedupConfig) extends Component {
  val io     = PageWriterSSIO(conf)

  val pwConf = conf.pwConf

  /** queue here to avoid blocking the wrap pgIn, which is also forked to BF & SHA3 */
  val frgmInQ    = io.pgStrmFrgmIn.queue(512) // 128 x 64 fragment = 8096
  val lookupResQ = io.lookupRes.queue(8)

  val instrDecoder = PageWriterInstrDecoder(conf)

  val decodedWaitingInstrQueue = StreamFifo(DecodedWaitingInstr(conf), 1 << pwConf.waitingQueueLogDepth)

  val decodedReadyInstrQueue = StreamFifo(DecodedReadyInstr(conf), 1 << pwConf.readyQueueLogDepth)

  // decoder + decoded instr Queue
  io.opStrmIn                        >> instrDecoder.io.rawInstrStream
  instrDecoder.io.readyInstrStream   >> decodedReadyInstrQueue.io.push
  instrDecoder.io.waitingInstrStream >> decodedWaitingInstrQueue.io.push
  
  val (lookupResToData, lookupResToInstr) = StreamFork2(lookupResQ)
  // output 0 Data in
  // dataIn = input page, drop when(lookup exist)
  // lookup exist = lookupRes.drop(not write).when(#ref > 1)
  // extract results only for write instr
  val insertionLookupRes = lookupResToData.throwWhen(!(lookupResToData.payload.opCode === DedupCoreOp.WRITE2FREE))
  val pgNeedStore        = Reg(Bool()) init False
  val pgNeedStoreValid   = Reg(Bool()) init False
  insertionLookupRes.ready := !pgNeedStoreValid
  when(frgmInQ.lastFire){
    // wait for next lookup Res
    // if insertionLookupRes valid
    insertionLookupRes.ready := True
    pgNeedStoreValid := False
  }
  when(insertionLookupRes.fire){
    pgNeedStore := !(insertionLookupRes.RefCount > 1)
    pgNeedStoreValid := True
  }

  io.SSDDataIn << frgmInQ.continueWhen(pgNeedStoreValid).throwWhen(!pgNeedStore)
  
  // output 1 Instr & header in, also Resp back to host
  val instrIssuer = PageWriterInstrIssuer(conf)
  instrIssuer.io.initEn              := io.initEn
  instrIssuer.io.readyInstrStream    << decodedReadyInstrQueue.io.pop
  instrIssuer.io.waitingInstrStream  << decodedWaitingInstrQueue.io.pop
  instrIssuer.io.lookupResStream     << lookupResToInstr

  // send instr to SSD and send resp back
  val mockSSDController = new Area{
    val forkedFullInstrStream = StreamFork2(instrIssuer.io.instrIssueStream)
    io.SSDInstrIn.translateFrom(forkedFullInstrStream._1){(storageInstr, fullInstr) =>
      storageInstr.SHA3Hash    := fullInstr.SHA3Hash
      storageInstr.RefCount    := fullInstr.RefCount
      storageInstr.SSDLBAStart := fullInstr.SSDLBA
      storageInstr.SSDLBALen   := fullInstr.hostLBALen
      when(fullInstr.opCode === DedupCoreOp.WRITE2FREE){
        storageInstr.opCode := (fullInstr.RefCount === 1) ? SSDOp.WRITE | SSDOp.UPDATEHEADER
      }.elsewhen(fullInstr.opCode === DedupCoreOp.ERASEREF){
        storageInstr.opCode := (fullInstr.RefCount === 0) ? SSDOp.ERASE | SSDOp.UPDATEHEADER
      }.otherwise{
        storageInstr.opCode := SSDOp.READ
      }
    }

    io.res.translateFrom(forkedFullInstrStream._2.queue(4)){(resp, fullInstr) =>
      resp.SHA3Hash      := fullInstr.SHA3Hash
      resp.RefCount      := fullInstr.RefCount
      resp.SSDLBA        := fullInstr.SSDLBA
      resp.hostLBAStart  := fullInstr.hostLBAStart
      resp.hostLBALen    := fullInstr.hostLBALen
      resp.opCode        := fullInstr.opCode

      when(fullInstr.opCode === DedupCoreOp.WRITE2FREE){
        resp.isExec := (fullInstr.RefCount === 1) ? True | False
      }.elsewhen(fullInstr.opCode === DedupCoreOp.ERASEREF){
       resp.isExec := (fullInstr.RefCount === 0) ? True | False
      }.otherwise{
        resp.isExec := True
      }
    }
  }
  // instrIssuer.io.instrIssueStream    >> bfLookupFSM.io.instrStrmIn
  
  // SSD output: read resp
  io.SSDDataOut.setBlocked()
}

case class CntDynamic(upBoundEx: UInt, incFlag: Bool) {
  val cnt = Reg(UInt(upBoundEx.getWidth bits)).init(0)
  val willOverflowIfInc = (cnt === upBoundEx -1)
  val willClear = False.allowOverride
  def clearAll(): Unit = willClear := True
  when(~willOverflowIfInc & incFlag) {
    cnt := cnt + 1
  }
  when(willClear) (cnt.clearAll())
}

case class MockSSD(conf: DedupConfig) extends Component {
  // val io = new Bundle{
  //   val dataIn        = slave Stream (Fragment(Bits(conf.wordSizeBit bits)))
  //   val dataOut       = master Stream (Fragment(Bits(conf.wordSizeBit bits)))
  //   // val instrIn       = 
  //   // val headerDataIn  = 
  //   // val headerDataOut = 
  //   // val headerInstrIn = 
  // }

}