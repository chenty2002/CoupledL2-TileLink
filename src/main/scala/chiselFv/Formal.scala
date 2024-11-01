package chiselFv

import chisel3.internal.sourceinfo.SourceInfo
import chisel3.{assert => cassert, _}
import freechips.rocketchip.diplomacy.LazyModuleImp


trait Formal {
  this: Module => 
  
  private val resetCounter = Module(new ResetCounter)
  resetCounter.io.clk := this.clock
  resetCounter.io.reset := this.reset
  val timeSinceReset = resetCounter.io.timeSinceReset
  val notChaos = resetCounter.io.notChaos


  def assert(cond: Bool, msg: String = "")
            (implicit sourceInfo: SourceInfo,
             compileOptions: CompileOptions): Unit = {
    when(notChaos) {
      cassert(cond, msg)
    }
  }

  def assertAt(n: UInt, cond: Bool, msg: String = "")
              (implicit sourceInfo: SourceInfo,
               compileOptions: CompileOptions): Unit = {
    when(notChaos && timeSinceReset === n) {
      cassert(cond, msg)
    }
  }

  def assertAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
                          (implicit sourceInfo: SourceInfo,
                           compileOptions: CompileOptions): Unit = {
    val next = RegInit(VecInit(Seq.fill(n)(false.B)))
    when(cond && notChaos) {
      next(0) := true.B
    }.otherwise {
      next(0) := false.B
    }
    for (i <- 1 until n) {
      next(i) := next(i - 1)
    }
    when(next(n - 1)) {
      assert(asert, msg)
    }
  }

  def assertNextStepWhen(cond: Bool, asert: Bool, msg: String = "")
                        (implicit sourceInfo: SourceInfo,
                         compileOptions: CompileOptions): Unit = {
    assertAfterNStepWhen(cond, 1, asert, msg)
  }

  def assertAlwaysAfterNStepWhen(cond: Bool, n: Int, asert: Bool, msg: String = "")
                                (implicit sourceInfo: SourceInfo,
                                 compileOptions: CompileOptions): Unit = {
    val next = RegInit(VecInit(Seq.fill(n)(false.B)))
    when(cond && notChaos) {
      next(0) := true.B
    }
    for (i <- 1 until n) {
      next(i) := next(i - 1)
    }
    when(next(n - 1)) {
      assert(asert, msg)
    }
  }

  def past[T <: Data](value: T, n: Int)(block: T => Any)
                     (implicit sourceInfo: SourceInfo,
                      compileOptions: CompileOptions): Unit = {
    when(notChaos && timeSinceReset >= n.U) {
      block(Delay(value, n))
    }
  }

  def initialReg(w: Int, v: Int): InitialReg = {
    val reg = Module(new InitialReg(w, v))
    reg.io.clk := clock
    reg.io.reset := reset
    reg
  }

  def anyconst(w: Int): UInt = {
    val cst = Module(new AnyConst(w))
    cst.io.out
  }

  def assertLivenessTimer(cond: Bool, reset: Bool, n: Int, msg: String = "")
                         (implicit sourceInfo: SourceInfo,
                          compileOptions: CompileOptions): Unit = {
    val timer = RegInit(0.U(64.W))
    when(reset) {
      timer := 1.U
    }.elsewhen(cond) {
      timer := timer + 1.U
    }
    when(notChaos) {
      cassert(timer <= n.U, msg)
    }
  }
}
