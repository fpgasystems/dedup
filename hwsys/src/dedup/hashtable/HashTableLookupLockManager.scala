package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._

object LockManagerOp extends SpinalEnum(binarySequential) {
  val ACQUIRE, RELEASE = newElement()
}

case class lockTableContent(htConf: HashTableConfig) extends Bundle{
  val lockIsActive = Bool()
  val lockedIdxBucket  = UInt(htConf.idxBucketWidth bits)
}

case class FSMLockRequest(htConf: HashTableConfig) extends Bundle {
  val idxBucket = UInt(htConf.idxBucketWidth bits)
  val FSMId     = UInt(log2Up(htConf.sizeFSMArray) bits)
  val opCode    = LockManagerOp()
}

case class HashTableLookupLockManagerIO(htConf: HashTableConfig) extends Bundle {
  val initEn      = in Bool()
  val initDone    = out Bool()

  val axiConf     = Axi4ConfigAlveo.u55cHBM
  // FSM array request
  val fsmArrayLockReq = in Vec(Stream(FSMLockRequest(htConf)), htConf.sizeFSMArray)
  val fsmArrayDRAMReq = in Vec(Axi4(axiConf), htConf.sizeFSMArray)
  /** DRAM interface */
  val axiMem      = master(Axi4(axiConf))
}

case class HashTableLookupLockManager(htConf: HashTableConfig) extends Component {

  val io = HashTableLookupLockManagerIO(htConf)

  /** default status of streams */
  io.fsmArrayLockReq.foreach(_.setBlocked())
  io.fsmArrayDRAMReq.foreach(_.setBlocked())
  io.axiMem.setIdle()

  /** Resource */
  val rInitDone = RegInit(False)
  io.initDone := rInitDone

  val memInitializer = new HashTableMemInitializer(htConf)

  val fsmLockReq = StreamArbiterFactory.roundRobin.transactionLock.on(io.fsmArrayLockReq)
  val parkingQueue = new StreamFifo(FSMLockRequest(htConf), 4)
  
  val lockTable = Vec(Reg(lockTableContent(htConf)), htConf.sizeFSMArray)
  lockTable.foreach{ content =>
    content.lockIsActive init False
    content.lockedIdxBucket init 0
  }

  def checkIsLocked(lockRequest: FSMLockRequest): Bool = {
    lockTable.sExist{ content =>
      (lockRequest.opCode === LockManagerOp.ACQUIRE) & (content.lockedIdxBucket === lockRequest.idxBucket) & content.lockIsActive
    }
  }

  val newReqIsLocked = checkIsLocked(fsmLockReq.payload)

  val parkingReqIsLocked = checkIsLocked(parkingQueue.io.pop.payload)

  val tableManager = new Area{
    val parkingReqReadyToFire = (!parkingReqIsLocked) & parkingQueue.io.pop.valid
    val newReqReadyToFire = (!newReqIsLocked) & fsmLockReq.valid
    
    val fsmLockReqSelect = newReqIsLocked ? U(1) | U(0)
    val dispatchedFSMReq = StreamDemux(fsmLockReq, fsmLockReqSelect, 2)

    parkingQueue.io.pop.ready := parkingReqReadyToFire
    dispatchedFSMReq(0).ready := newReqReadyToFire & (!parkingReqReadyToFire)
    dispatchedFSMReq(1) >> parkingQueue.io.push
    when(parkingQueue.io.pop.fire){
      val instr = parkingQueue.io.pop.payload
      lockTable(instr.FSMId).lockIsActive := (instr.opCode === LockManagerOp.ACQUIRE) ? True | False
      lockTable(instr.FSMId).lockedIdxBucket := (instr.opCode === LockManagerOp.ACQUIRE) ? instr.idxBucket | U(0)
    }
  }

  // need ini and axi connect

  // val dispatchedInstrStream = StreamDispatcherSequential(io.instrStrmIn, htConf.sizeFSMArray)

  // val fsmResBufferArray = Array.fill(htConf.sizeFSMArray)(new StreamFifo(HashTableLookupFSMRes(htConf), 4))
  // val fsmArray = Array.tabulate(htConf.sizeFSMArray){idx => 
  //   val fsmInstance = new HashTableLookupFSM(htConf,idx)
  //   // connect instr dispatcher to fsm
  //   dispatchedInstrStream(idx).queue(4) >> fsmInstance.io.instrStrmIn
  //   // connect fsm results to output
  //   fsmInstance.io.res >> fsmResBufferArray(idx).io.push
  //   fsmInstance
  // }

  // io.res << StreamArbiterFactory.sequentialOrder.transactionLock.on(Array.tabulate(htConf.sizeFSMArray)(idx => fsmResBufferArray(idx).io.pop))
  // val resMerger = new Area{
  //   val fsmSelect = Counter(htConf.sizeFSMArray, io.res.fire)
  //   val dispatchedInstrStream = StreamDemux(io.instrStrmIn, fsmSelect, htConf.sizeFSMArray)
  // }

  // val lockTable



}