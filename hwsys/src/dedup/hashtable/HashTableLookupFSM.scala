package dedup
package hashtable

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.bus.amba4.axi._

// object HashTableOp extends SpinalEnum {
//   val INSERT, LOOKUP, ERASE = newElement()
// }

// case class HashTabCmd (htConf: HashTableConfig) extends Bundle {
//   val verb = HashTabVerb()
//   val hashVal = Bits(htConf.hashValWidth bits)
//   val isPostInst = Bool()
// }

case class HashTableLookupFSMInstr(htConf: HashTableConfig) extends Bundle{
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val opCode = DedupCoreOp()
}

case class HashTableLookupFSMRes (htConf: HashTableConfig) extends Bundle {
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val RefCount = UInt(htConf.ptrWidth bits)
  val SSDLBA   = UInt(htConf.ptrWidth bits)
  val opCode   = DedupCoreOp()
}

case class HashTableBucketMetaData (htConf: HashTableConfig) extends Bundle {
  // Block Addr = entry idx
  val head    = UInt(htConf.ptrWidth bits)
  val tail    = UInt(htConf.ptrWidth bits)
  // lenth of the linked list
  val len     = UInt(htConf.ptrWidth bits)
  val padding = UInt((512 - 3 * htConf.ptrWidth) bits)
}

case class HashTableBucketMetaDataNoPadding (htConf: HashTableConfig) extends Bundle {
  // Block Addr = entry idx
  val head    = UInt(htConf.ptrWidth bits)
  val tail    = UInt(htConf.ptrWidth bits)
  // lenth of the linked list
  val len     = UInt(htConf.ptrWidth bits)
}

case class HashTableEntry (htConf: HashTableConfig) extends Bundle {
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val RefCount = UInt(htConf.ptrWidth bits)
  val SSDLBA   = UInt(htConf.ptrWidth bits)
  val next     = UInt(htConf.ptrWidth bits)
  val padding  = UInt((htConf.hashValWidth - 3 * htConf.ptrWidth) bits)
}

case class HashTableEntryNoPadding (htConf: HashTableConfig) extends Bundle {
  val SHA3Hash = Bits(htConf.hashValWidth bits)
  val RefCount = UInt(htConf.ptrWidth bits)
  val SSDLBA   = UInt(htConf.ptrWidth bits)
  val next     = UInt(htConf.ptrWidth bits)
}

// case class DRAMRdCmd(htConf: HashTableConfig) extends Bundle {
//   val addr   = UInt(config.addressWidth bits)
//   val id     = UInt(config.idWidth bits)
//   val len    = UInt(8 bits)
//   val size   = UInt(3 bits)
//   val burst  = Bits(2 bits)
//   val addr = UInt(64 bits)
//   val id
//   val nEntry = UInt(htConf.bucketOffsWidth bits)
//   io.axiMem.ar.addr := rDRAMRdCmd.memOffs + cntAxiRdCmd * burstLen * io.axiConf.dataWidth/8
//   io.axiMem.ar.id   := 0
//   io.axiMem.ar.len  := burstLen-1
//   io.axiMem.ar.size := log2Up(io.axiConf.dataWidth/8)
//   io.axiMem.ar.setBurstINCR()
//   io.axiMem.ar.valid := False
// }

// case class DRAMWrCmd(htConf: HashTableConfig) extends Bundle {
//   val memOffs = UInt(64 bits) // hash entry offset in DRAM
//   val ptrVal = UInt(htConf.ptrWidth bits) // page pointer in storage
//   val hashVal = Bits(htConf.hashValWidth bits)
// }

// size = #entry inside
//hashTableOffset = global memory offset of the hash table
// index -> entry0 -> entry1 -> ......

case class HashTableLookupFSMIO(htConf: HashTableConfig) extends Bundle {
  val initEn      = in Bool()
  // execution results
  val instrStrmIn = slave Stream(HashTableLookupFSMInstr(htConf))
  val res         = master Stream(HashTableLookupFSMRes(htConf))
  // interface the lock manager
  val lockReq     = master Stream(FSMLockRequest(htConf))
  // interface to allocator
  val mallocIdx   = slave Stream(UInt(htConf.ptrWidth bits))
  val freeIdx     = master Stream(UInt(htConf.ptrWidth bits))
  /** DRAM interface */
  val axiConf     = Axi4ConfigAlveo.u55cHBM
  val axiMem      = master(Axi4(axiConf))
}

case class HashTableLookupFSM (htConf: HashTableConfig, FSMId: Int = 0) extends Component {

  val rFSMId = Reg(UInt(log2Up(htConf.sizeFSMArray) bits)) init U(FSMId) allowUnsetRegToAvoidLatch

  val io = HashTableLookupFSMIO(htConf)

  /** default status of strems */
  io.instrStrmIn.setBlocked()
  io.res.setIdle()
  io.lockReq.setIdle()
  io.mallocIdx.setBlocked()
  io.freeIdx.setIdle()
  io.axiMem.setIdle()

  val instr      = Reg(HashTableLookupFSMInstr(htConf))
  val idxBucket  = instr.SHA3Hash(htConf.idxBucketWidth-1 downto 0).asUInt // lsb as the bucket index
  // val bucketMetaDataAddr = Reg(UInt(64 bits)) init (0)

  // val dramRdCmdQ = StreamFifo(DRAMRdCmd(htConf), htConf.cmdQDepth)
  // val dramWrCmdQ = StreamFifo(DRAMWrCmd(htConf), htConf.cmdQDepth)

  /** default */
  // dramRdCmdQ.io.push.setIdle()
  // dramRdCmdQ.io.pop.setBlocked()

  // dramWrCmdQ.io.push.setIdle()
  // dramWrCmdQ.io.pop.setBlocked()


  val fsm = new StateMachine {
    val FETCH_INSTRUCTION                                                         = new State with EntryPoint
    val LOCK_ACQUIRE, FETCH_METADATA, FETCH_ENTRY, EXEC, WRITE_BACK, LOCK_RELEASE = new State

    always{
      when(io.initEn){
        goto(FETCH_INSTRUCTION)
      }
    }

    FETCH_INSTRUCTION.whenIsActive {
      io.instrStrmIn.ready := True
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()
      // instruction fetching
      when(io.instrStrmIn.fire){
        instr := io.instrStrmIn.payload
        goto(LOCK_ACQUIRE)
      }
    }

    LOCK_ACQUIRE.whenIsActive {
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()

      io.lockReq.valid             := True
      io.lockReq.payload.idxBucket := idxBucket
      io.lockReq.payload.FSMId     := rFSMId.resized
      io.lockReq.payload.opCode    := LockManagerOp.ACQUIRE
      // acquire lock from lock manager
      when(io.lockReq.fire){
        goto(FETCH_METADATA)
      }
    }

    val fetchMetaDataAXIIssued   = Reg(Bool())
    val fetchMetaDataAXIReceived = Reg(Bool())
    val bucketMetaData           = Reg(HashTableBucketMetaDataNoPadding(htConf))
    val nextEntryIdx             = Reg(UInt(htConf.ptrWidth bits))

    FETCH_METADATA.onEntry{
      fetchMetaDataAXIIssued   := False
      fetchMetaDataAXIReceived := False
      bucketMetaData.head      := 0
      bucketMetaData.tail      := 0
      bucketMetaData.len       := 0
      nextEntryIdx             := 0
    }

    FETCH_METADATA.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()
      
      when(!fetchMetaDataAXIIssued){
        val metaDataStartAddr    = htConf.hashTableOffset + (idxBucket << htConf.bucketMetaDataAddrBitShift)
        val burstLen             = 1
        io.axiMem.readCmd.addr  := metaDataStartAddr.resized
        io.axiMem.readCmd.id    := rFSMId.resized
        io.axiMem.readCmd.len   := burstLen-1
        io.axiMem.readCmd.size  := htConf.bucketMetaDataAddrBitShift
        io.axiMem.readCmd.setBurstINCR()
        io.axiMem.readCmd.valid := True

        when(io.axiMem.readCmd.fire){
          fetchMetaDataAXIIssued := True
        }
      }.elsewhen(!fetchMetaDataAXIReceived){
        // command issued, wait for response
        io.axiMem.readRsp.ready  := True
        when(io.axiMem.readRsp.fire){
          fetchMetaDataAXIReceived := True
          val decodedMetaData = HashTableBucketMetaDataNoPadding(htConf)
          decodedMetaData.assignFromBits(io.axiMem.readRsp.data, bucketMetaData.getBitsWidth - 1, 0)
          bucketMetaData := decodedMetaData
          nextEntryIdx   := decodedMetaData.head
          goto(FETCH_ENTRY)
        }
      }
    }

    val cntFetchEntryAXIIssued   = Counter(htConf.ptrWidth bits, io.axiMem.readCmd.fire)
    val cntFetchEntryAXIReceived = Counter(htConf.ptrWidth bits, io.axiMem.readRsp.fire)

    val lookupIsExist     = Reg(Bool()) // lookup result
    // current fetched entry
    val currentEntry      = Reg(HashTableEntryNoPadding(htConf))
    val currentEntryIdx   = Reg(UInt(htConf.ptrWidth bits))
    val currentEntryValid = Reg(Bool())
    // buffered prev entry
    val prevEntry         = RegNextWhen(currentEntry, io.axiMem.readRsp.fire)
    val prevEntryIdx      = RegNextWhen(currentEntryIdx, io.axiMem.readRsp.fire)
    val prevEntryValid    = RegNextWhen(currentEntryValid, io.axiMem.readRsp.fire)

    FETCH_ENTRY.onEntry{
      cntFetchEntryAXIIssued.clear()
      cntFetchEntryAXIReceived.clear()
      
      lookupIsExist         := False
      
      currentEntry.SHA3Hash := 0
      currentEntry.RefCount := 0
      currentEntry.SSDLBA   := 0
      currentEntry.next     := 0
      currentEntryIdx       := 0
      currentEntryValid     := False
      
      prevEntry.SHA3Hash    := 0
      prevEntry.RefCount    := 0
      prevEntry.SSDLBA      := 0
      prevEntry.next        := 0
      prevEntryIdx          := 0
      prevEntryValid        := False
    }

    FETCH_ENTRY.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()

      // fetch entry
      when((bucketMetaData.head === 0 ) | (bucketMetaData.tail === 0 ) | (bucketMetaData.len === 0 )){
        // empty bucket, no lookup anymore
        goto(EXEC)
      }.elsewhen(nextEntryIdx === 0){
        // we are at the last entry, no lookup anymore
        goto(EXEC)
      }.otherwise{
        // bucket not empty, and not the last entry, fetch entry
        val entryStartAddr       = htConf.hashTableContentOffset + (nextEntryIdx << htConf.entryAddrBitShift)
        val burstLen             = 1
        io.axiMem.readCmd.addr  := entryStartAddr.resized
        io.axiMem.readCmd.id    := rFSMId.resized
        io.axiMem.readCmd.len   := burstLen-1
        io.axiMem.readCmd.size  := htConf.entryAddrBitShift
        io.axiMem.readCmd.setBurstINCR()
        io.axiMem.readCmd.valid := (cntFetchEntryAXIIssued === cntFetchEntryAXIReceived) & (!lookupIsExist)

        io.axiMem.readRsp.ready := True
        when(io.axiMem.readRsp.fire){
          val decodedHashTableEntry = HashTableEntryNoPadding(htConf)
          decodedHashTableEntry.assignFromBits(io.axiMem.readRsp.data, decodedHashTableEntry.getBitsWidth - 1, 0)
          currentEntry      := decodedHashTableEntry
          currentEntryIdx   := nextEntryIdx
          currentEntryValid := True
          nextEntryIdx      := decodedHashTableEntry.next

          when(decodedHashTableEntry.SHA3Hash === instr.SHA3Hash){
            // find
            lookupIsExist := True
            goto(EXEC)
          }
        }
      }
    }

    val mallocDone = Reg(Bool())
    val freeDone   = Reg(Bool())

    // bucket status
    val resIsEmptyBucket  = (!prevEntryValid) & (!currentEntryValid)
    val resIsFirstElement = (!prevEntryValid) & (currentEntryValid)
    val resIsNormal       = (prevEntryValid) & (currentEntryValid)

    // control write back True => need to write back
    val needMetaDataWriteBack     = Reg(Bool())
    val needCurrentEntryWriteBack = Reg(Bool())
    val needPrevEntryWriteBack    = Reg(Bool())

    EXEC.onEntry{
      mallocDone                    := False
      freeDone                      := False
      // is need write back(updated)
      needMetaDataWriteBack         := False
      needCurrentEntryWriteBack     := False
      needPrevEntryWriteBack        := False
    }

    EXEC.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()

      when(instr.opCode === DedupCoreOp.WRITE2FREE){
        // insert
        when(lookupIsExist){
          // exist, update ref counter
          currentEntry.RefCount := currentEntry.RefCount + 1
          // only need to write back current entry
          needMetaDataWriteBack      := False
          needCurrentEntryWriteBack  := True
          needPrevEntryWriteBack     := False
          goto(WRITE_BACK)
        }.otherwise{
          // new, insert
          io.mallocIdx.ready    := !mallocDone
          // update everything
          needMetaDataWriteBack      := True
          needCurrentEntryWriteBack  := True
          needPrevEntryWriteBack     := True

          /* no 0 in the idx, 0 == invalid
            need fire & (!(io.mallocIdx.payload === U(0)))
            but we move the 0-detection to outer lookupEngine module
          */
          when(io.mallocIdx.fire){
            mallocDone := True
            val newIdx  = io.mallocIdx.payload
            // update new entry
            currentEntry.SHA3Hash := instr.SHA3Hash
            currentEntry.RefCount := 1
            currentEntry.SSDLBA   := newIdx
            currentEntry.next     := 0
            currentEntryIdx       := newIdx
            currentEntryValid     := True
            // update old entry
            prevEntry.SHA3Hash    := currentEntry.SHA3Hash
            prevEntry.RefCount    := currentEntry.RefCount
            prevEntry.SSDLBA      := currentEntry.SSDLBA
            prevEntry.next        := newIdx
            prevEntryIdx          := currentEntryIdx
            prevEntryValid        := currentEntryValid
            // update metadata: if empty, change head
            bucketMetaData.head    := resIsEmptyBucket ? newIdx | bucketMetaData.head
            bucketMetaData.tail    := newIdx
            bucketMetaData.len     := bucketMetaData.len + 1
            goto(WRITE_BACK)
          }
        }
      }.elsewhen(instr.opCode === DedupCoreOp.ERASEREF){
        // erase
        when(lookupIsExist){
          when(currentEntry.RefCount > 1){
            // only update ref counter
            currentEntry.RefCount := currentEntry.RefCount - 1
            // only update current entry
            needMetaDataWriteBack      := False
            needCurrentEntryWriteBack  := True
            needPrevEntryWriteBack     := False
            goto(WRITE_BACK)
          }.otherwise{
            // GC
            io.freeIdx.valid     := !freeDone
            io.freeIdx.payload   := currentEntryIdx

            // update metadata and prevpage, current page is deleted
            needMetaDataWriteBack     := True
            needCurrentEntryWriteBack := False
            needPrevEntryWriteBack    := True

            when(io.freeIdx.fire){
              freeDone := True
              // free current entry
              currentEntry.SHA3Hash := currentEntry.SHA3Hash
              currentEntry.RefCount := 0
              currentEntry.SSDLBA   := currentEntry.SSDLBA  
              currentEntry.next     := currentEntry.next    
              currentEntryIdx       := currentEntryIdx
              // update old entry (if exist)
              prevEntry.SHA3Hash    := prevEntry.SHA3Hash
              prevEntry.RefCount    := prevEntry.RefCount
              prevEntry.SSDLBA      := prevEntry.SSDLBA
              prevEntry.next        := currentEntry.next
              prevEntryIdx          := prevEntryIdx
              // update metadata: if empty, change head
              bucketMetaData.head    := (resIsFirstElement) ? nextEntryIdx | bucketMetaData.head
              bucketMetaData.tail    := (currentEntryIdx === bucketMetaData.tail) ? (prevEntryValid ? prevEntryIdx | U(0)) | bucketMetaData.tail
              bucketMetaData.len     := (bucketMetaData.len > 0) ? (bucketMetaData.len - 1) | U(0)

              goto(WRITE_BACK)
            }
          }
        }.otherwise{
          goto(WRITE_BACK)
        }
      }
    }

    val doneMetaDataWriteBackAddr     = Reg(Bool())
    val doneCurrentEntryWriteBackAddr = Reg(Bool())
    val donePrevEntryWriteBackAddr    = Reg(Bool())

    val doneMetaDataWriteBackData     = Reg(Bool())
    val doneCurrentEntryWriteBackData = Reg(Bool())
    val donePrevEntryWriteBackData    = Reg(Bool())

    // doneMetaDataWriteBackAddr     := !needMetaDataWriteBack   
    // doneCurrentEntryWriteBackAddr := !(needCurrentEntryWriteBack & currentEntryValid)
    // donePrevEntryWriteBackAddr    := !(needPrevEntryWriteBack & prevEntryValid)

    // doneMetaDataWriteBackData     := !needMetaDataWriteBack   
    // doneCurrentEntryWriteBackData := !(needCurrentEntryWriteBack & currentEntryValid)
    // donePrevEntryWriteBackData    := !(needPrevEntryWriteBack & prevEntryValid)

    val cntWriteResponse = Counter(4, io.axiMem.writeRsp.fire)

    WRITE_BACK.onEntry{
      doneMetaDataWriteBackAddr     := False
      doneCurrentEntryWriteBackAddr := False
      donePrevEntryWriteBackAddr    := False

      doneMetaDataWriteBackData     := False
      doneCurrentEntryWriteBackData := False
      donePrevEntryWriteBackData    := False

      cntWriteResponse.clear() 
    }

    WRITE_BACK.whenIsActive{
      io.instrStrmIn.setBlocked()
      io.res.setIdle()
      io.lockReq.setIdle()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()

      io.axiMem.writeCmd.setIdle()
      io.axiMem.writeData.setIdle()
      io.axiMem.readCmd.setIdle()
      io.axiMem.readRsp.setBlocked()

      io.axiMem.writeRsp.ready := True
      val responseNeeded = Vec(needMetaDataWriteBack, needCurrentEntryWriteBack & currentEntryValid, needPrevEntryWriteBack & prevEntryValid).sCount(True)
      when(cntWriteResponse === responseNeeded){
        goto(LOCK_RELEASE)
      }

      when(needMetaDataWriteBack & !(doneMetaDataWriteBackAddr & doneMetaDataWriteBackData)){
        // write back metadata
        val metaDataStartAddr     = htConf.hashTableOffset + (idxBucket << htConf.bucketMetaDataAddrBitShift)
        io.axiMem.writeCmd.addr  := metaDataStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.bucketMetaDataAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needMetaDataWriteBack & (!doneMetaDataWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          doneMetaDataWriteBackAddr := True
        }

        io.axiMem.writeData.data  := (bucketMetaData.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.bucketMetaDataByteSize)-1
        io.axiMem.writeData.valid := needMetaDataWriteBack & (!doneMetaDataWriteBackData)

        when(io.axiMem.writeData.fire){
          doneMetaDataWriteBackData := True
        }
      }.elsewhen(needCurrentEntryWriteBack & currentEntryValid & !(doneCurrentEntryWriteBackAddr & doneCurrentEntryWriteBackData)){
        // write back current page
        val currentEntryStartAddr = htConf.hashTableContentOffset + (currentEntryIdx << htConf.entryAddrBitShift)
        io.axiMem.writeCmd.addr  := currentEntryStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.entryAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needCurrentEntryWriteBack & currentEntryValid & (!doneCurrentEntryWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          doneCurrentEntryWriteBackAddr := True
        }

        io.axiMem.writeData.data  := (currentEntry.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.entryByteSize)-1
        io.axiMem.writeData.valid := needCurrentEntryWriteBack & currentEntryValid & (!doneCurrentEntryWriteBackData)

        when(io.axiMem.writeData.fire){
          doneCurrentEntryWriteBackData := True
        }
      }.elsewhen(needPrevEntryWriteBack & prevEntryValid & !(donePrevEntryWriteBackAddr & donePrevEntryWriteBackData)){
        // write back prev page
        val prevEntryStartAddr    = htConf.hashTableContentOffset + (prevEntryIdx << htConf.entryAddrBitShift)
        io.axiMem.writeCmd.addr  := prevEntryStartAddr.resized
        io.axiMem.writeCmd.id    := rFSMId.resized
        io.axiMem.writeCmd.len   := 0
        io.axiMem.writeCmd.size  := htConf.entryAddrBitShift
        io.axiMem.writeCmd.setBurstINCR()
        io.axiMem.writeCmd.valid := needPrevEntryWriteBack & prevEntryValid & (!donePrevEntryWriteBackAddr)
        
        when(io.axiMem.writeCmd.fire){
          donePrevEntryWriteBackAddr := True
        }


        io.axiMem.writeData.data  := (prevEntry.asBits).resized
        io.axiMem.writeData.last  := True
        io.axiMem.writeData.strb  := (BigInt(1)<<htConf.entryByteSize)-1
        io.axiMem.writeData.valid := needPrevEntryWriteBack & prevEntryValid & (!donePrevEntryWriteBackData)

        when(io.axiMem.writeData.fire){
          donePrevEntryWriteBackData := True
        }
      }
    }

    val lockReleased = Reg(Bool())
    val resFired     = Reg(Bool())

    LOCK_RELEASE.onEntry{
      lockReleased := False   
      resFired     := False
    }

    LOCK_RELEASE.whenIsActive{
      // release lock
      io.instrStrmIn.setBlocked()
      io.mallocIdx.setBlocked()
      io.freeIdx.setIdle()
      io.axiMem.setIdle()

      io.lockReq.valid             := !lockReleased
      io.lockReq.payload.idxBucket := idxBucket
      io.lockReq.payload.FSMId     := rFSMId.resized
      io.lockReq.payload.opCode    := LockManagerOp.RELEASE
      // acquire lock from lock manager
      when(io.lockReq.fire){
        lockReleased := True
      }

      io.res.valid             := !resFired
      io.res.payload.SHA3Hash  := currentEntry.SHA3Hash
      io.res.payload.RefCount  := currentEntry.RefCount
      io.res.payload.SSDLBA    := currentEntry.SSDLBA
      io.res.payload.opCode    := instr.opCode
      // acquire lock from lock manager
      when(io.res.fire){
        resFired := True
      }

      when(lockReleased & resFired){
        goto(FETCH_INSTRUCTION)
      }
    }
  }
}