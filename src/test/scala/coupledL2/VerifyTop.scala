package coupledL2

import chisel3._
import chisel3.stage.ChiselStage
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.tl2chi._
import coupledL2.tl2tl.TL2TLCoupledL2
import coupledL2.tl2tl.{Slice => TLSlice}
import coupledL2AsL1.prefetch.CoupledL2AsL1PrefParam
import coupledL2AsL1.tl2tl.{TL2TLCoupledL2 => TLCoupledL2AsL1}
import coupledL2AsL1.tl2chi.{TL2CHICoupledL2 => CHICoupledL2AsL1}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile.MaxHartIdBits
import freechips.rocketchip.tilelink._
import huancun._
import org.chipsalliance.cde.config._
import utility._
import dataclass.data


class VerifyTop_L2L3L2()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  println("class VerifyTop_L2L3L2:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL2 = 2

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l0_nodes = (0 until nrL2).map(i => createClientNode(s"l0$i", 32))

  val coupledL2AsL1 = (0 until nrL2).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = 1))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zip(l2_nodes).zipWithIndex map {
    case ((l1d, l2), i) => l2 := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      dontTouch(l2AsL1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val l1_offsetBits = 1
    val l1_bankBits = 0
    val l1_setBits = 1
    val l1_tagBits = 3

    def parseL1Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l1_offsetBits + l1_bankBits)
      val tag = set >> l1_setBits
      (tag(l1_tagBits - 1, 0), set(l1_setBits - 1, 0), offset(l1_offsetBits - 1, 0))
    }

    val l2_offsetBits = 1
    val l2_bankBits = 0
    val l2_setBits = 2
    val l2_tagBits = 2

    def parseL2Address(x: UInt): (UInt, UInt, UInt) = {
      val offset = x
      val set = offset >> (l2_offsetBits + l2_bankBits)
      val tag = set >> l2_setBits
      (tag(l2_tagBits - 1, 0), set(l2_setBits - 1, 0), offset(l2_offsetBits - 1, 0))
    }

    val l1_stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(2, 2)(0.U(2.W))))
    val l1_tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(2, 2)(0.U(3.W))))
    
    val l2_stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val l2_tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    val l2_dataArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(8)(0.U(16.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.addSink(l1_stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(l1_tagArray(i), s"tagArray_${i}")
      BoringUtils.addSink(l2_dataArray(i), s"dataArray_${i}")
    }

    for (i <- nrL2 until 2*nrL2) {
      BoringUtils.addSink(l2_stateArray(i-nrL2), s"stateArray_${i}")
      BoringUtils.addSink(l2_tagArray(i-nrL2), s"tagArray_${i}")
    }

    
    val mshrs = 4

    class MSHR_signal_bundle extends Bundle {
      val status_bits_set = UInt(l2_setBits.W)
      val status_bits_metaTag = UInt(l2_tagBits.W)
      val status_bits_reqTag = UInt(l2_tagBits.W)
      val status_bits_needRepl = Bool()
      val status_bits_w_c_resp = Bool()
      val status_bits_w_d_resp = Bool()
      val status_valid = Bool()
    }

    val MSHR_signals = Seq.fill(mshrs)(WireDefault(0.U.asTypeOf(new MSHR_signal_bundle)))

    val l2_MSHR_prop_patch_inclusive = Wire(Bool())
    val l2_MSHR_prop_patch_consistency = Wire(Bool())

    coupledL2(0).module.slices(0) match {
      case s: TLSlice =>
        println("--- TLSLICE ---")
        for (i <- 0 until mshrs) {
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.set, Seq(MSHR_signals(i).status_bits_set))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.metaTag, Seq(MSHR_signals(i).status_bits_metaTag))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.reqTag, Seq(MSHR_signals(i).status_bits_reqTag))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.needsRepl, Seq(MSHR_signals(i).status_bits_needRepl))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.w_c_resp, Seq(MSHR_signals(i).status_bits_w_c_resp))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.bits.w_d_resp, Seq(MSHR_signals(i).status_bits_w_d_resp))
          BoringUtils.bore(s.mshrCtl.mshrs(i).io.status.valid, Seq(MSHR_signals(i).status_valid))
        }

        l2_MSHR_prop_patch_inclusive := Cat(MSHR_signals.map { m =>
          m.status_bits_set === 0.U &&
          m.status_bits_metaTag === 0.U &&
          m.status_bits_needRepl === true.B &&
          m.status_bits_w_c_resp === true.B &&
          m.status_valid === true.B
        }).orR

        l2_MSHR_prop_patch_consistency := Cat(MSHR_signals.map { m =>
          m.status_bits_set === 0.U &&
          m.status_bits_reqTag === 0.U &&
          m.status_bits_w_d_resp === true.B &&
          m.status_valid === true.B
        }).orR
      case _ =>
        l2_MSHR_prop_patch_inclusive := false.B
        l2_MSHR_prop_patch_consistency := false.B
    }


    def l2_mutual(addr: UInt, state1: UInt, state2: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArray(0)(set).zip(l2_stateArray(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= MetaData.INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArray(1)(set).zip(l2_stateArray(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= MetaData.INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      assert(PopCount(l2_hit_vec_0) <= 1.U)
      assert(PopCount(l2_hit_vec_1) <= 1.U)

      assert(!(hit0 && l2_stateArray(0)(set)(way0) === state1 &&
        hit1 && l2_stateArray(1)(set)(way1) === state2))
    }

    def mutual_specs(): Unit = {
      l2_mutual(0.U(32.W), MetaData.TIP, MetaData.TIP)
      l2_mutual(0.U(32.W), MetaData.TIP, MetaData.TRUNK)
      l2_mutual(0.U(32.W), MetaData.INVALID, MetaData.TIP)
      l2_mutual(0.U(32.W), MetaData.BRANCH, MetaData.BRANCH)
      l2_mutual(0.U(32.W), MetaData.TIP, MetaData.BRANCH)
      l2_mutual(0.U(32.W), MetaData.TRUNK, MetaData.INVALID)
    }

    def l1l2_inclusive(addr: UInt): Unit = {
      val (l1_tag, l1_set, l1_offset) = parseL1Address(addr)
      val (l2_tag, l2_set, l2_offset) = parseL2Address(addr)
      
      val l1_hit_vec = l1_tagArray(0)(l1_set).zip(l1_stateArray(0)(l1_set)).map {
        case (tag, state) =>
          tag === l1_tag && state =/= MetaData.INVALID
      }
      val l1_hit = l1_hit_vec.reduce(_ || _)
      val l1_way = OHToUInt(l1_hit_vec)

      val l2_hit_vec = l2_tagArray(0)(l2_set).zip(l2_stateArray(0)(l2_set)).map {
        case (tag, state) =>
          tag === l2_tag && state =/= MetaData.INVALID
      }
      val l2_hit = l2_hit_vec.reduce(_ || _)
      val l2_way = OHToUInt(l2_hit_vec)

      assert(PopCount(l1_hit_vec) <= 1.U)
      assert(PopCount(l2_hit_vec) <= 1.U)

      when(l1_hit && l1_stateArray(0)(l1_set)(l1_way) =/= MetaData.INVALID) {
        assert((l2_hit && l2_stateArray(0)(l2_set)(l2_way) =/= MetaData.INVALID) || l2_MSHR_prop_patch_inclusive)
      }
    }

    def inclusive_spec(): Unit = {
      l1l2_inclusive(0.U(32.W))
    }

    def l2_consistency(addr: UInt): Unit = {
      val (tag, set, offset) = parseL2Address(addr)

      val l2_hit_vec_0 = l2_tagArray(0)(set).zip(l2_stateArray(0)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= MetaData.INVALID
      }

      val hit0 = l2_hit_vec_0.reduce(_ || _)
      val way0 = OHToUInt(l2_hit_vec_0)

      val l2_hit_vec_1 = l2_tagArray(1)(set).zip(l2_stateArray(1)(set)).map {
        case (l2_tag, l2_state) =>
          tag === l2_tag && l2_state =/= MetaData.INVALID
      }

      val hit1 = l2_hit_vec_1.reduce(_ || _)
      val way1 = OHToUInt(l2_hit_vec_1)

      assert(PopCount(l2_hit_vec_0) <= 1.U)
      assert(PopCount(l2_hit_vec_1) <= 1.U)

      val arrayIdx0 = Cat(way0, set)
      val arrayIdx1 = Cat(way1, set)

      when(hit0 && l2_stateArray(0)(set)(way0) === MetaData.BRANCH && 
        hit1 && l2_stateArray(1)(set)(way1) === MetaData.BRANCH) {
        assert(l2_dataArray(0)(arrayIdx0) === l2_dataArray(1)(arrayIdx1) || l2_MSHR_prop_patch_consistency)
      }
    }

    def consistency_spec(): Unit = {
      l2_consistency(0.U(32.W))
    }

    mutual_specs()
    inclusive_spec()
    consistency_spec()
  }
}

object VerifyTop_L2L3L2 extends App {

  println("object VerifyTop_L2L3L2:")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3L2()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3L2")
  )
}

class VerifyTop_L2L3()(implicit p: Parameters) extends LazyModule {

  /* L1D  
   *  |   
   * L2
   *  |
   * L3
   */

  println("class VerifyTop_L2L3:")

  override lazy val desiredName: String = "VerifyTop"
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val nrL1 = 1
  val nrL2 = 1

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l0_nodes = (0 until nrL1).map(i => createClientNode(s"l0$i", 32))

  val coupledL2AsL1 = (0 until nrL1).map(i => LazyModule(new TLCoupledL2AsL1()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l1$i",
      ways = 2,
      sets = 2,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(),
      prefetch = Seq(CoupledL2AsL1PrefParam()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l1_nodes = coupledL2AsL1.map(_.node)

  val coupledL2 = (0 until nrL2).map(i => LazyModule(new TL2TLCoupledL2()(baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      name = s"l2$i",
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      mshrs = 4,
      hartId = i
    )
    case BankBitsKey => 0
  }))))
  val l2_nodes = coupledL2.map(_.node)

  val l3 = LazyModule(new HuanCun()(baseConfig(1).alterPartial({
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 2,
      sets = 4,
      blockBytes = 2,
      channelBytes = TLChannelBeatBytes(1),
      inclusive = false,
      clientCaches = (0 until nrL2).map(i =>
        CacheParameters(
          name = s"l2",
          sets = 4,
          ways = 2 + 2,
          blockGranularity = log2Ceil(2)
        ),
      ),
      echoField = Seq(DirtyField()),
      simulation = true,
      mshrs = 6
    )
  })))


  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0x1FL), beatBytes = 1))

  l0_nodes.zip(l1_nodes).zipWithIndex map {
    case ((l0, l1), i) => l1 := l0
  }

  l1_nodes.zipWithIndex map {
    case (l1d, i) => l2_nodes(0) := TLLogger(s"L2_L1_${i}") := TLBuffer() := l1d
  }

  l2_nodes.zipWithIndex map {
    case (l2, i) => xbar := TLLogger(s"L3_L2_${i}") := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(1, 2) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      TLLogger(s"MEM_L3") :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this) with Formal {
    val timer = WireDefault(0.U(64.W))
    val logEnable = WireDefault(false.B)
    val clean = WireDefault(false.B)
    val dump = WireDefault(false.B)

    dontTouch(timer)
    dontTouch(logEnable)
    dontTouch(clean)
    dontTouch(dump)

    coupledL2AsL1.foreach(_.module.io.debugTopDown := DontCare)
    coupledL2.foreach(_.module.io.debugTopDown := DontCare)


    coupledL2AsL1.foreach(_.module.io.l2_tlb_req <> DontCare)
    coupledL2.foreach(_.module.io.l2_tlb_req <> DontCare)

    coupledL2AsL1.foreach(_.module.io.hartId <> DontCare)
    coupledL2.foreach(_.module.io.hartId <> DontCare)

    l1_nodes.foreach { node =>
      val (l1_in, _) = node.in.head
      dontTouch(l1_in)
    }

    // Input signals for formal verification
    val io = IO(new Bundle {
      val topInputRandomAddrs = Input(Vec(nrL2, UInt(5.W)))
      val topInputNeedT = Input(Vec(nrL2, Bool()))
    })

    coupledL2AsL1.zipWithIndex.foreach { case (l2AsL1, i) =>
      l2AsL1.module.io.prefetcherInputRandomAddr := io.topInputRandomAddrs(i)
      l2AsL1.module.io.prefetcherNeedT := io.topInputNeedT(i)
      dontTouch(l2AsL1.module.io)
    }

    val verify_timer = RegInit(0.U(50.W))
    verify_timer := verify_timer + 1.U
    val dir_resetFinish = WireDefault(false.B)
    BoringUtils.addSink(dir_resetFinish, "coupledL2_0_dir")
    assume(verify_timer < 100.U || dir_resetFinish)

    val stateArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))
    val tagArray = Seq.fill(nrL2)(WireDefault(VecInit.fill(4, 2)(0.U(2.W))))

    for (i <- 0 until nrL2) {
      BoringUtils.addSink(stateArray(i), s"stateArray_${i}")
      BoringUtils.addSink(tagArray(i), s"tagArray_${i}")
    }
  }
}

object VerifyTop_L2L3 extends App {

  println("object VerifyTop_L2L3:")

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })
  val top = DisableMonitors(p => LazyModule(new VerifyTop_L2L3()(p)))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", "Verilog/L2L3")
  )
}
