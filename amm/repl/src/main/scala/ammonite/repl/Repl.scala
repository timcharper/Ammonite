package ammonite.repl

import java.io.{InputStream, InputStreamReader, OutputStream}

import ammonite.runtime._
import ammonite.terminal.Filter
import ammonite.util.Util.newLine
import ammonite.util._

import ammonite.interp.{Interpreter, Preprocessor}

import scala.annotation.tailrec

class Repl(input: InputStream,
           output: OutputStream,
           info: OutputStream,
           error: OutputStream,
           storage: Storage,
           defaultPredef: String,
           mainPredef: String,
           wd: ammonite.ops.Path,
           welcomeBanner: Option[String],
           replArgs: Seq[Bind[_]] = Nil,
           remoteLogger: Option[RemoteLogger]) {

  val prompt = Ref("@ ")

  val frontEnd = Ref[FrontEnd](AmmoniteFrontEnd(Filter.empty))

  var history = new History(Vector())

  val (colors, printStream, errorPrintStream, printer) =
    Interpreter.initPrinters(output, info, error, true)



  val argString = replArgs.zipWithIndex.map{ case (b, idx) =>
    s"""
    val ${b.name} =
      ammonite.repl.ReplBridge.value.replArgs($idx).value.asInstanceOf[${b.typeTag.tpe}]
    """
  }.mkString(newLine)

  val interp: Interpreter = new Interpreter(
    printer,
    storage,
    Seq(
      PredefInfo(Name("DefaultPredef"), defaultPredef, true, None),
      PredefInfo(Name("ArgsPredef"), argString, false, None),
      PredefInfo(Name("MainPredef"), mainPredef, false, Some(wd))
    ),
    i => {
      val replApi = new ReplApiImpl(
        i,
        frontEnd().width,
        frontEnd().height,
        colors,
        prompt,
        frontEnd,
        history,
        new SessionApiImpl(i.compilerManager.frames),
        replArgs
      )
      Seq(("ammonite.repl.ReplBridge", "repl", replApi, () => replApi.sess.save()))
    },
    wd
  )

  val reader = new InputStreamReader(input)

  def action() = for{
    (code, stmts) <- frontEnd().action(
      input,
      reader,
      output,
      colors().prompt()(prompt()).render,
      colors(),
      interp.compilerManager.pressy.complete(_, interp.eval.imports.toString, _),
      storage.fullHistory(),
      addHistory = (code) => if (code != "") {
        storage.fullHistory() = storage.fullHistory() :+ code
        history = history :+ code
      }
    )
    _ <- Signaller("INT") { interp.mainThread.stop() }
    out <- interp.processLine(code, stmts, s"cmd${interp.eval.getCurrentLine}.sc")
  } yield {
    printStream.println()
    out
  }

  def run(): Any = {
    welcomeBanner.foreach(printStream.println)
    @tailrec def loop(): Any = {
      val actionResult = action()
      remoteLogger.foreach(_.apply("Action"))
      interp.handleOutput(actionResult)

      actionResult match{
        case Res.Exit(value) =>
          printStream.println("Bye!")
          value
        case Res.Failure(ex, msg) => printer.error(msg)
          loop()
        case Res.Exception(ex, msg) =>
          printer.error(
            Repl.showException(ex, colors().error(), fansi.Attr.Reset, colors().literal())
          )
          printer.error(msg)
          loop()
        case _ =>
          loop()
      }
    }
    loop()
  }

  def beforeExit(exitValue: Any): Any = {
    Function.chain(interp.beforeExitHooks)(exitValue)
  }
}

object Repl{


  def highlightFrame(f: StackTraceElement,
                     error: fansi.Attrs,
                     highlightError: fansi.Attrs,
                     source: fansi.Attrs) = {
    val src =
      if (f.isNativeMethod) source("Native Method")
      else if (f.getFileName == null) source("Unknown Source")
      else source(f.getFileName) ++ error(":") ++ source(f.getLineNumber.toString)

    val prefix :+ clsName = f.getClassName.split('.').toSeq
    val prefixString = prefix.map(_+'.').mkString("")
    val clsNameString = clsName //.replace("$", error("$"))
    val method =
      error(prefixString) ++ highlightError(clsNameString) ++ error(".") ++
        highlightError(f.getMethodName)

    fansi.Str(s"  ") ++ method ++ "(" ++ src ++ ")"
  }
  def showException(ex: Throwable,
                    error: fansi.Attrs,
                    highlightError: fansi.Attrs,
                    source: fansi.Attrs) = {
    val cutoff = Set("$main", "evaluatorRunPrinter")
    val traces = Ex.unapplySeq(ex).get.map(exception =>
      error(exception.toString + newLine +
        exception
          .getStackTrace
          .takeWhile(x => !cutoff(x.getMethodName))
          .map(highlightFrame(_, error, highlightError, source))
          .mkString(newLine))
    )
    traces.mkString(newLine)
  }
}
