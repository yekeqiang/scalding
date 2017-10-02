package com.twitter.scalding.typed.cascading_backend


import cascading.flow.FlowDef
import cascading.operation.{ Debug, Operation }
import cascading.pipe.{ CoGroup, Each, Pipe, HashJoin }
import cascading.tuple.{ Fields, Tuple => CTuple, TupleEntry }
import com.stripe.dagon.{ FunctionK, HCache, Memoize, Rule, Dag }
import com.twitter.scalding.TupleConverter.{ singleConverter, tuple2Converter }
import com.twitter.scalding.TupleSetter.{ singleSetter, tup2Setter }
import com.twitter.scalding.{
  CleanupIdentityFunction, Config, Dsl, Field, FlatMapFunction, FlowStateMap, GroupBuilder,
  HadoopMode, LineNumber, IterableSource, MapsideReduce, Mode, RichFlowDef,
  RichPipe, TupleConverter, TupleGetter, TupleSetter, TypedBufferOp, WrappedJoiner, Write
}
import com.twitter.scalding.typed._
import com.twitter.scalding.serialization.{
  Boxed,
  BoxedOrderedSerialization,
  CascadingBinaryComparator,
  EquivSerialization,
  OrderedSerialization,
  WrappedSerialization
}
import java.util.WeakHashMap
import scala.collection.mutable.{ Map => MMap }

object CascadingBackend {
  import TypedPipe._

  private val valueField: Fields = new Fields("value")
  private val kvFields: Fields = new Fields("key", "value")
  private val f0: Fields = new Fields(java.lang.Integer.valueOf(0))

  private def tuple2Conv[K, V](ord: Ordering[K]): TupleConverter[(K, V)] =
    ord match {
      case _: OrderedSerialization[_] =>
        tuple2Converter[Boxed[K], V].andThen { kv =>
          (kv._1.get, kv._2)
        }
      case _ => tuple2Converter[K, V]
    }

  private def valueConverter[V](optOrd: Option[Ordering[_ >: V]]): TupleConverter[V] =
    optOrd.map {
      case _: OrderedSerialization[_] =>
        TupleConverter.singleConverter[Boxed[V]].andThen(_.get)
      case _ => TupleConverter.singleConverter[V]
    }.getOrElse(TupleConverter.singleConverter[V])

  private def keyConverter[K](ord: Ordering[K]): TupleConverter[K] =
    ord match {
      case _: OrderedSerialization[_] =>
        TupleConverter.singleConverter[Boxed[K]].andThen(_.get)
      case _ => TupleConverter.singleConverter[K]
    }

  private def keyGetter[K](ord: Ordering[K]): TupleGetter[K] =
    ord match {
      case _: OrderedSerialization[K] =>
        new TupleGetter[K] {
          def get(tup: CTuple, i: Int) = tup.getObject(i).asInstanceOf[Boxed[K]].get
        }
      case _ => TupleGetter.castingGetter
    }

  /**
   * If we are using OrderedComparable, we need to box the key
   * to prevent other serializers from handling the key
   */
  private def getBoxFnAndOrder[K](ordser: OrderedSerialization[K], flowDef: FlowDef): (K => Boxed[K], BoxedOrderedSerialization[K]) = {
    // We can only supply a cacheKey if the equals and hashcode are known sane
    val (boxfn, cls) = Boxed.nextCached[K](if (ordser.isInstanceOf[EquivSerialization[_]]) Some(ordser) else None)
    val boxordSer = BoxedOrderedSerialization(boxfn, ordser)

    WrappedSerialization.rawSetBinary(List((cls, boxordSer)),
      {
        case (k: String, v: String) =>
          FlowStateMap.mutate(flowDef) { st =>
            val newSt = st.addConfigSetting(k + cls, v)
            (newSt, ())
          }
      })
    (boxfn, boxordSer)
  }

  /**
   * Check if the Ordering is an OrderedSerialization, if so box in a Boxed so hadoop and cascading
   * can dispatch the right serialization
   */
  private def maybeBox[K, V](ord: Ordering[K], flowDef: FlowDef)(op: (TupleSetter[(K, V)], Fields) => Pipe): Pipe =
    ord match {
      case ordser: OrderedSerialization[K] =>
        val (boxfn, boxordSer) = getBoxFnAndOrder[K](ordser, flowDef)

        val ts = tup2Setter[(Boxed[K], V)].contraMap { kv1: (K, V) => (boxfn(kv1._1), kv1._2) }
        val keyF = new Fields("key")
        keyF.setComparator("key", new CascadingBinaryComparator(boxordSer))
        op(ts, keyF)
      case _ =>
        val ts = tup2Setter[(K, V)]
        val keyF = Field.singleOrdered("key")(ord)
        op(ts, keyF)
    }

   private case class CascadingPipe[T](pipe: Pipe,
    fields: Fields,
    @transient localFlowDef: FlowDef, // not serializable.
    converter: TupleConverter[T]) {

    /**
     * merge the flowDef into this new flowdef an make sure the tuples
     * have the structure defined by setter
     */
    def toPipe[T](f: Fields, fd: FlowDef, setter: TupleSetter[T]): Pipe = ???
  }

  /**
   * we want to cache renderings of some TypedPipe to Pipe so cascading
   * will see them as the same. Without this, it is very easy to have
   * a lot of recomputation. Ideally we would plan an entire graph
   * at once, and not need a static cache here, but currently we still
   * plan one TypedPipe at a time.
   */
  private class PipeCache {
    private[this] val pipeCache = new WeakHashMap[TypedPipe[Any], Map[Mode, CascadingPipe[Any]]]()

    def cacheGet[T](t: TypedPipe[T], m: Mode)(p: FlowDef => CascadingPipe[T]): CascadingPipe[T] = {
      def add(mmc: Map[Mode, CascadingPipe[Any]]): CascadingPipe[T] = {
        val emptyFD = new FlowDef
        val res = p(emptyFD)
        pipeCache.put(t, mmc + (m -> res.asInstanceOf[CascadingPipe[Any]]))
        res
      }

      pipeCache.synchronized {
        pipeCache.get(t) match {
          case null => add(Map.empty)
          case somemap if somemap.contains(m) => somemap(m).asInstanceOf[CascadingPipe[T]]
          case missing => add(missing)
        }
      }
    }
  }
  private[this] val pipeCache = new PipeCache

  private type CurryResult[T] = Mode => CascadingPipe[_ <: T]

  private def compile[T]: FunctionK[TypedPipe, CurryResult] =
    Memoize.functionK[TypedPipe, CurryResult](
      new Memoize.RecursiveK[TypedPipe, CurryResult] {
        def toFunction[T] = {
          case (cp@CrossPipe(_, _), rec) =>
            rec(cp.viaHashJoin)
          case (cv@CrossValue(_, _), rec) =>
            rec(cv.viaHashJoin)
          case (DebugPipe(p), rec) =>
            rec(p).andThen { inner =>
              inner.copy(pipe = new Each(inner.pipe, new Debug))
            }
          case (EmptyTypedPipe, rec) =>
            // just use an empty iterable pipe.
            rec(TypedPipe.from(List.empty[T]))
          case (fk@FilterKeys(_, _), rec) =>
            def go[K, V](node: FilterKeys[K, V]): CurryResult[(K, V)] = {
              val rewrite = Filter[(K, V)](node.input, FlatMappedFn.FilterKeysToFilter(node.fn))
              rec(rewrite)
            }
            go(fk)
          case (f@Filter(_, _), rec) =>
            // hand holding for type inference
            def go[T1 <: T](f: Filter[T1]): CurryResult[T] =
              // TODO, maybe it is better to tell cascading we are
              // doing a filter here: https://github.com/cwensel/cascading/blob/wip-4.0/cascading-core/src/main/java/cascading/operation/Filter.java
              rec(FlatMapped(f.input, FlatMappedFn.fromFilter(f.fn)))

            go(f)
          case (f@FlatMapValues(_, _), rec) =>
            def go[K, V, U](node: FlatMapValues[K, V, U]): CurryResult[T] =
              rec(FlatMapped[(K, V), (K, U)](node.input, FlatMappedFn.FlatMapValuesToFlatMap(node.fn)))

            go(f)
          case (fm@FlatMapped(_, _), rec) =>
            // TODO we can optimize a flatmapped input directly and skip some tupleconverters
            def go[A, B <: T](fm: FlatMapped[A, B]): CurryResult[T] =
              rec(fm.input).andThen {
                case CascadingPipe(pipe, initF, fd, conv) =>
                  CascadingPipe[B](RichPipe(pipe).flatMapTo[A, T](initF -> f0)(fm.fn)(TupleConverter.asSuperConverter(conv), singleSetter),
                    f0,
                    fd,
                    singleConverter)
              }

            go(fm)
          case (ForceToDisk(input), rec) =>
            rec(input).andThen { cp =>
              cp.copy(pipe = RichPipe(cp.pipe).forceToDisk)
            }
          case (Fork(input), rec) =>
            // fork doesn't mean anything here since we are already planning each TypedPipe to
            // something in cascading. Fork is an optimizer level operation
            rec(input)
          case (IterablePipe(iter), _) =>
            { mode =>
              val fd = new FlowDef
              val pipe = IterableSource[T](iter, f0)(singleSetter, singleConverter).read(fd, mode)
              CascadingPipe[T](pipe, f0, fd, singleConverter)
            }
          case (f@MapValues(_, _), rec) =>
            def go[K, A, B](fn: MapValues[K, A, B]): CurryResult[_ <: (K, B)] =
              rec(Mapped[(K, A), (K, B)](fn.input, FlatMappedFn.MapValuesToMap(fn.fn)))

            go(f)
          case (Mapped(input, fn), rec) =>
            rec(FlatMapped(input, FlatMappedFn.fromMap(fn)))

          case (m@MergedTypedPipe(_, _), rec) =>
            OptimizationRules.unrollMerge(m) match {
              case Nil => rec(EmptyTypedPipe)
              case h :: tail =>
                val recH = rec(h)
                val recTail = tail.map(rec(_))
                // TODO: a better optimization is to not materialize this
                // node at all if there is no fan out since groupBy and cogroupby
                // can accept multiple inputs

                // we need to make sure all the converters are the same, or
                // remap them to the singleConverter
                ???
            }
          case (SourcePipe(typedSrc), rec) =>
            {  mode =>
              val fd = new FlowDef
              val pipe = typedSrc.read(fd, mode)
              CascadingPipe[T](pipe, typedSrc.sourceFields, fd, typedSrc.converter[T])
            }
          case (sblk@SumByLocalKeys(_, _), rec) =>
            def go[K, V](sblk: SumByLocalKeys[K, V]): CurryResult[_ <: (K, V)] =
              rec(sblk.input).andThen { cp =>
                val localFD = new FlowDef
                val cpKV: Pipe = cp.toPipe[(K, V)](kvFields, localFD, tup2Setter)
                val msr = new MapsideReduce(sblk.semigroup, new Fields("key"), valueField, None)(singleConverter[V], singleSetter[V])
                val kvpipe = RichPipe(cpKV).eachTo(kvFields -> kvFields) { _ => msr }
                CascadingPipe[(K, V)](kvpipe, kvFields, localFD, tuple2Converter)
              }
            go(sblk)
          case (TrappedPipe(input, sink, conv), rec) =>
            val inputRes = rec(input)

            { mode =>
              val cp = inputRes(mode)
              // TODO: with diamonds in the graph, this might not be correct
              val fd = new FlowDef
              val pp: Pipe = cp.toPipe(sink.sinkFields, fd, sink.setter)
              val pipe = RichPipe.assignName(pp)
              fd.addTrap(pipe, sink.createTap(Write)(mode))
              CascadingPipe(pipe, sink.sinkFields, fd, conv)
            }
          case (WithDescriptionTypedPipe(input, descr, dedup), rec) =>
            rec(input).andThen { cp =>
              // TODO: the WithDescriptions node should allow multiple descriptions
              // so we can use the optimizer to combine all into one go
              // this is currently wrong now, since dedupping won't work
              cp.copy(pipe = applyDescriptions(cp.pipe, List((descr, dedup))))
            }
          case (WithOnComplete(input, fn), rec) =>
            rec(input).andThen { cp =>
              val next = new Each(cp.pipe, Fields.ALL, new CleanupIdentityFunction(fn), Fields.REPLACE)
              cp.copy(pipe = next)
            }
          case (hcg@HashCoGroup(_, _, _), rec) =>
            def go[K, V1, V2, R](hcg: HashCoGroup[K, V1, V2, R]): CurryResult[(K, R)] =
              planHashJoin(hcg.left,
                hcg.right,
                hcg.joiner,
                hcg.right.keyOrdering,
                rec)

            go(hcg)
          case (r@ReduceStepPipe(_), rec) =>
            planReduceStep(r, rec)

          case (cgp@CoGroupedPipe(_), rec) => ???
        }
      })
      /*

      case cgp@CoGroupedPipe(_) =>
        def go[K, V](cgp: CoGroupedPipe[K, V]): Pipe = {
          // TODO we can push up filterKeys on both the left and right
          // and mapValues/flatMapValues on the result
          val cp = cacheGet(cgp, mode) { implicit fd =>
            val kvPipe = planCoGroup(cgp.cogrouped, fd, mode)
            CascadingPipe(kvPipe, kvFields, fd, tuple2Converter[K, V])
          }
          finish(cp, rest, descriptions)
        }
        go(cgp)
    }
        }
      })
*/

  private def applyDescriptions(p: Pipe, descriptions: List[(String, Boolean)]): Pipe = {
    val ordered = descriptions.collect { case (d, false) => d }.reverse
    val unordered = descriptions.collect { case (d, true) => d }.distinct.sorted

    RichPipe.setPipeDescriptions(p, ordered ::: unordered)
  }

  final def toPipe[U](p: TypedPipe[U], fieldNames: Fields)(implicit flowDef: FlowDef, mode: Mode, setter: TupleSetter[U]): Pipe = {

    /**
     * These are rules we should apply to any TypedPipe before handing
     * to cascading. These should be a bit conservative in that they
     * should be highly likely to improve the graph.
     */
    val standardCascadingRules: Rule[TypedPipe] = Rule.empty // TODO make this

    val optP = Dag.applyRule(p, OptimizationRules.toLiteral, standardCascadingRules)

    // Now that we have an optimized pipe, convert it to a CascadingPipe[U]:

    val compiler = compile

    val cp: CascadingPipe[_ <: U] = compile(optP)(mode)

    cp.toPipe(fieldNames, flowDef, setter)
  }

//     import pipeCache.cacheGet

//     def singlePipe[T](t: TypedPipe[T], force: Boolean = false): CascadingPipe[T] =
//       cacheGet(t, mode) { localFD =>
//         val pipe = toPipe(t, f0)(localFD, mode, singleSetter)
//         val p = if (force) RichPipe(pipe).forceToDisk else pipe
//         CascadingPipe[T](p, f0, localFD, singleConverter)
//       }

//     /*
//      * This creates a mapping operation on a Pipe. It does so
//      * by merging the local FlowDef of the CascadingPipe into
//      * the one passed to this method, then running the FlatMappedFn
//      * and finally applying the descriptions.
//      */
//     def finish[T](cp: CascadingPipe[T],
//       rest: FlatMappedFn[T, U],
//       descriptions: List[(String, Boolean)]): Pipe = {

//       Dsl.flowDefToRichFlowDef(flowDef).mergeFrom(cp.localFlowDef)
//       val withRest = RichPipe(cp.pipe)
//         .flatMapTo[T, U](cp.fields -> fieldNames)(rest)(cp.converter, setter)

//       applyDescriptions(withRest, descriptions)
//     }

//     def loop[T](t: TypedPipe[T], rest: FlatMappedFn[T, U], descriptions: List[(String, Boolean)]): Pipe = t match {
//       case cp@CrossPipe(_, _) => loop(cp.viaHashJoin, rest, descriptions)

//       case cv@CrossValue(_, _) => loop(cv.viaHashJoin, rest, descriptions)

//       case DebugPipe(p) =>
//         // There is really little that can be done here but println
//         loop(p.map { t => println(t); t }, rest, descriptions)

//       case EmptyTypedPipe =>
//         // just use an empty iterable pipe.
//         // Note, rest is irrelevant
//         val empty = IterableSource(Iterable.empty, fieldNames)(setter, singleConverter[U]).read(flowDef, mode)
//         applyDescriptions(empty, descriptions)

//       case fk@FilterKeys(_, _) =>
//         def go[K, V](node: FilterKeys[K, V]): Pipe = node match {
//           case FilterKeys(IterablePipe(iter), fn) =>
//             loop[(K, V)](IterablePipe(iter.filter { case (k, v) => fn(k) }), rest, descriptions)
//           case _ =>
//             loop[(K, V)](node.input, rest.runAfter(FlatMapping.filterKeys(node.fn)), descriptions)
//         }
//         go(fk)

//       case f@Filter(_, _) =>
//         // hand holding for type inference
//         def go[T1 <: T](f: Filter[T1]) = f match {
//           case Filter(IterablePipe(iter), fn) => loop(IterablePipe(iter.filter(fn)), rest, descriptions)
//           case _ =>
//             loop[T1](f.input, rest.runAfter(FlatMapping.filter(f.fn)), descriptions)
//         }
//         go(f)

//       case f@FlatMapValues(_, _) =>
//         def go[K, V, U](node: FlatMapValues[K, V, U]): Pipe = {
//           // don't capture node, which is a TypedPipe, which we avoid serializing
//           val fn = node.fn
//           loop(node.input, rest.runAfter(
//             FlatMapping.FlatM[(K, V), (K, U)] { case (k, v) =>
//               fn(v).map((k, _))
//             }), descriptions)
//         }

//         go(f)

//       case FlatMapped(prev, fn) =>
//         loop(prev, rest.runAfter(FlatMapping.FlatM(fn)), descriptions)

//       case ForceToDisk(EmptyTypedPipe) => loop(EmptyTypedPipe, rest, descriptions)
//       case ForceToDisk(i@IterablePipe(iter)) => loop(i, rest, descriptions)
//       case ForceToDisk(pipe) => finish(singlePipe(pipe, force = true), rest, descriptions)

//       case Fork(EmptyTypedPipe) => loop(EmptyTypedPipe, rest, descriptions)
//       case Fork(i@IterablePipe(iter)) => loop(i, rest, descriptions)
//       case Fork(pipe) => finish(singlePipe(pipe), rest, descriptions)

//       case IterablePipe(iterable) =>
//         val toSrc = IterableSource(iterable, f0)(singleSetter[T], singleConverter[T])
//         loop(SourcePipe(toSrc), rest, descriptions)

//       case f@MapValues(_, _) =>
//         def go[K, V, U](node: MapValues[K, V, U]): Pipe = {
//           // don't capture node, which is a TypedPipe, which we avoid serializing
//           val mvfn = node.fn
//           loop(node.input, rest.runAfter(
//             FlatMapping.Map[(K, V), (K, U)] { case (k, v) => (k, mvfn(v)) }), descriptions)
//         }

//         go(f)

//       case Mapped(input, fn) => loop(input, rest.runAfter(FlatMapping.Map(fn)), descriptions)

//       case MergedTypedPipe(left, right) =>
//         @annotation.tailrec
//         def allMerged[A](m: TypedPipe[A],
//           stack: List[TypedPipe[A]],
//           acc: List[TypedPipe[A]],
//           ds: List[(String, Boolean)]): (List[TypedPipe[A]], List[(String, Boolean)]) = m match {
//             case MergedTypedPipe(left, right) =>
//               allMerged(left, right :: stack, acc, ds)
//             case EmptyTypedPipe => stack match {
//               case Nil => (acc, ds)
//               case h :: t => allMerged(h, t, acc, ds)
//             }
//             case WithDescriptionTypedPipe(p, desc, dedup) =>
//               allMerged(p, stack, acc, (desc, dedup) :: ds)
//             case notMerged =>
//               allMerged(EmptyTypedPipe, stack, notMerged :: acc, ds)
//           }
//         val (unmerged, ds) = allMerged(left, right :: Nil, Nil, Nil)
//         // check for repeated pipes
//         val uniquePipes: List[TypedPipe[T]] = unmerged
//           .groupBy(identity)
//           .mapValues(_.size)
//           .map {
//             case (pipe, 1) => pipe
//             case (pipe, cnt) => pipe.flatMap(List.fill(cnt)(_).iterator)
//           }
//           .toList

//         uniquePipes match {
//           case Nil => loop(EmptyTypedPipe, rest, ds ::: descriptions)
//           case h :: Nil => loop(h, rest, ds ::: descriptions)
//           case otherwise =>
//             // push all the remaining flatmaps up:
//             val pipes = otherwise.map(loop(_, rest, Nil))
//             // make the cascading pipe
//             // TODO: a better optimization is to not materialize this
//             // node at all if there is no fan out since groupBy and cogroupby
//             // can accept multiple inputs
//             val merged = new cascading.pipe.Merge(pipes.map(RichPipe.assignName): _*)
//             applyDescriptions(merged, ds ::: descriptions)
//         }
//       case src@SourcePipe(_) =>
//         def go[A](sp: SourcePipe[A]): CascadingPipe[A] =
//           cacheGet[A](sp, mode) { implicit localFD =>
//             val source = sp.source
//             val pipe = source.read(localFD, mode)
//             CascadingPipe[A](pipe, source.sourceFields, localFD, source.converter[A])
//           }
//         finish(go(src), rest, descriptions)

//       case slk@SumByLocalKeys(_, _) =>
//         def sum[K, V](sblk: SumByLocalKeys[K, V]): CascadingPipe[(K, V)] =
//           cacheGet(sblk, mode) { implicit localFD =>
//             val pairPipe = toPipe(sblk.input, kvFields)(localFD, mode, tup2Setter)
//             val msr = new MapsideReduce(sblk.semigroup, new Fields("key"), valueField, None)(singleConverter[V], singleSetter[V])
//             val kvpipe = RichPipe(pairPipe).eachTo(kvFields -> kvFields) { _ => msr }
//             CascadingPipe[(K, V)](kvpipe, kvFields, localFD, tuple2Converter)
//           }
//         finish(sum(slk), rest, descriptions)

//       case tp@TrappedPipe(_, _, _) =>
//         def go[A](tp: TrappedPipe[A], r: FlatMappedFn[A, U]): Pipe = {
//           val cp = cacheGet(tp, mode) { implicit fd =>
//             val sfields = tp.sink.sinkFields
//             // TODO: with diamonds in the graph, this might not be correct
//             val pp = toPipe[A](tp.input, sfields)(fd, mode, tp.sink.setter)
//             val pipe = RichPipe.assignName(pp)
//             flowDef.addTrap(pipe, tp.sink.createTap(Write)(mode))
//             CascadingPipe[A](pipe, sfields, fd, tp.conv)
//           }
//           finish(cp, r, descriptions)
//         }
//         go(tp, rest)

//       case WithDescriptionTypedPipe(pipe, description, dedup) =>
//         loop(pipe, rest, (description, dedup) :: descriptions)

//       case WithOnComplete(pipe, fn) =>
//         val planned = loop(pipe, rest, descriptions)
//         new Each(planned, Fields.ALL, new CleanupIdentityFunction(fn), Fields.REPLACE)

//       case hcg@HashCoGroup(_, _, _) =>
//         def go[K, V1, V2, R](hcg: HashCoGroup[K, V1, V2, R]): Pipe = {
//           // TODO we can push up filterKeys on both the left and right
//           // and mapValues/flatMapValues on the result
//           val cp = cacheGet(hcg, mode) { implicit fd =>
//             val kvPipe = planHashJoin(hcg.left,
//               hcg.right,
//               hcg.joiner,
//               hcg.right.keyOrdering,
//               fd,
//               mode)
//             CascadingPipe(kvPipe, kvFields, fd, tuple2Converter[K, R])
//           }
//           finish(cp, rest, descriptions)
//         }
//         go(hcg)

//       case cgp@CoGroupedPipe(_) =>
//         def go[K, V](cgp: CoGroupedPipe[K, V]): Pipe = {
//           // TODO we can push up filterKeys on both the left and right
//           // and mapValues/flatMapValues on the result
//           val cp = cacheGet(cgp, mode) { implicit fd =>
//             val kvPipe = planCoGroup(cgp.cogrouped, fd, mode)
//             CascadingPipe(kvPipe, kvFields, fd, tuple2Converter[K, V])
//           }
//           finish(cp, rest, descriptions)
//         }
//         go(cgp)

//       case r@ReduceStepPipe(_) =>
//         planReduceStep(r, mode) match {
//           case Right(cp) => finish(cp, rest, descriptions)
//           case Left(tp) => loop(tp, rest, descriptions)
//         }
//     }

//     RichPipe(loop(p, FlatMappedFn.identity[U], Nil)).applyFlowConfigProperties(flowDef)
//   }

  private def planCoGroup[K, R](cg: CoGrouped[K, R], flowDef: FlowDef, mode: Mode): Pipe = {
    import cg._

    // Cascading handles the first item in join differently, we have to see if it is repeated
    val firstCount = inputs.count(_ == inputs.head)

    import Dsl._
    import RichPipe.assignName

    /*
     * we only want key and value.
     * Cascading requires you have the same number coming in as out.
     * in the first case, we introduce (null0, null1), in the second
     * we have (key1, value1), but they are then discarded:
     */
    def outFields(inCount: Int): Fields =
      List("key", "value") ++ (0 until (2 * (inCount - 1))).map("null%d".format(_))

    // Make this stable so the compiler does not make a closure
    val ord = keyOrdering

    val newPipe = maybeBox[K, Any](ord, flowDef) { (tupset, ordKeyField) =>
      if (firstCount == inputs.size) {
        /**
         * This is a self-join
         * Cascading handles this by sending the data only once, spilling to disk if
         * the groups don't fit in RAM, then doing the join on this one set of data.
         * This is fundamentally different than the case where the first item is
         * not repeated. That case is below
         */
        val NUM_OF_SELF_JOINS = firstCount - 1
        new CoGroup(assignName(toPipe[(K, Any)](inputs.head, kvFields)(flowDef, mode,
          tupset)),
          ordKeyField,
          NUM_OF_SELF_JOINS,
          outFields(firstCount),
          WrappedJoiner(new DistinctCoGroupJoiner(firstCount, keyGetter(ord), joinFunction)))
      } else if (firstCount == 1) {

        def keyId(idx: Int): String = "key%d".format(idx)
        /**
         * As long as the first one appears only once, we can handle self joins on the others:
         * Cascading does this by maybe spilling all the streams other than the first item.
         * This is handled by a different CoGroup constructor than the above case.
         */
        def renamePipe(idx: Int, p: TypedPipe[(K, Any)]): Pipe =
          toPipe[(K, Any)](p, List(keyId(idx), "value%d".format(idx)))(flowDef, mode,
            tupset)

        // This is tested for the properties we need (non-reordering)
        val distincts = CoGrouped.distinctBy(inputs)(identity)
        val dsize = distincts.size
        val isize = inputs.size

        def makeFields(id: Int): Fields = {
          val comp = ordKeyField.getComparators.apply(0)
          val fieldName = keyId(id)
          val f = new Fields(fieldName)
          f.setComparator(fieldName, comp)
          f
        }

        val groupFields: Array[Fields] = (0 until dsize)
          .map(makeFields)
          .toArray

        val pipes: Array[Pipe] = distincts
          .zipWithIndex
          .map { case (item, idx) => assignName(renamePipe(idx, item)) }
          .toArray

        val cjoiner = if (isize != dsize) {
          // avoid capturing anything other than the mapping ints:
          val mapping: Map[Int, Int] = inputs.zipWithIndex.map {
            case (item, idx) =>
              idx -> distincts.indexWhere(_ == item)
          }.toMap

          new CoGroupedJoiner(isize, keyGetter(ord), joinFunction) {
            val distinctSize = dsize
            def distinctIndexOf(orig: Int) = mapping(orig)
          }
        } else {
          new DistinctCoGroupJoiner(isize, keyGetter(ord), joinFunction)
        }

        new CoGroup(pipes, groupFields, outFields(dsize), WrappedJoiner(cjoiner))
      } else {
        /**
         * This is non-trivial to encode in the type system, so we throw this exception
         * at the planning phase.
         */
        sys.error("Except for self joins, where you are joining something with only itself,\n" +
          "left-most pipe can only appear once. Firsts: " +
          inputs.collect { case x if x == inputs.head => x }.toString)
      }
    }
    /*
       * the CoGrouped only populates the first two fields, the second two
       * are null. We then project out at the end of the method.
       */
    val pipeWithRedAndDescriptions = {
      RichPipe.setReducers(newPipe, reducers.getOrElse(-1))
      RichPipe.setPipeDescriptions(newPipe, descriptions)
      newPipe.project(kvFields)
    }
    pipeWithRedAndDescriptions
  }

  private def planHashJoin[K, V1, V2, R](left: TypedPipe[(K, V1)],
    right: HashJoinable[K, V2],
    joiner: (K, V1, Iterable[V2]) => Iterator[R],
    keyOrdering: Ordering[K],
    rec: FunctionK[TypedPipe, CurryResult]): CurryResult[(K, R)] = {

    /**
     * Checks the transform to deduce if it is safe to skip the force to disk.
     * If the FlatMappedFn is an identity operation then we can skip
     * For map and flatMap we can't definitively infer if it is OK to skip the forceToDisk.
     * Thus we just go ahead and forceToDisk in those two cases - users can opt out if needed.
     */
     def canSkipEachOperation(eachOperation: Operation[_]): Boolean =
      eachOperation match {
        case f: FlatMapFunction[_, _] =>
          f.getFunction match {
            case fmp: FlatMappedFn[_, _] if (FlatMappedFn.asId(fmp).isDefined) =>
              // This is an operation that is doing nothing
              true
            case _ =>
              false
          }
        case _: CleanupIdentityFunction => true
        case _ => false
      }

    /**
     * Computes if it is safe to skip a force to disk (only if the user hasn't turned this off using
     * Config.HashJoinAutoForceRight).
     * If we know the pipe is persisted,we can safely skip. If the Pipe is an Each operator, we check
     * if the function it holds can be skipped and we recurse to check its parent pipe.
     * Recursion handles situations where we have a couple of Each ops in a row.
     * For example: pipe.forceToDisk.onComplete results in: Each -> Each -> Checkpoint
     */
    def isSafeToSkipForceToDisk(pipe: Pipe): Boolean = {
      import cascading.pipe._

      pipe match {
        case eachPipe: Each =>
          if (canSkipEachOperation(eachPipe.getOperation)) {
            //need to recurse down to see if parent pipe is ok
            RichPipe.getSinglePreviousPipe(eachPipe).exists(prevPipe => isSafeToSkipForceToDisk(prevPipe))
          } else false
        case _: Checkpoint => true
        case _: GroupBy => true
        case _: CoGroup => true
        case _: Every => true
        case p if RichPipe.isSourcePipe(p) => true
        case _ => false
      }
    }

    val leftCR: CurryResult[(K, V1)] = rec(left)
    val rightCR = rec(right.mapped)

    { mode =>

      val getHashJoinAutoForceRight: Boolean =
        mode match {
          case h: HadoopMode =>
            val config = Config.fromHadoop(h.jobConf)
            config.getHashJoinAutoForceRight
          case _ => false //default to false
        }


      val fd = new FlowDef

      /**
       * Returns a Pipe for the mapped (rhs) pipe with checkpointing (forceToDisk) applied if needed.
       * Currently we skip checkpointing if we're confident that the underlying rhs Pipe is persisted
       * (e.g. a source / Checkpoint / GroupBy / CoGroup / Every) and we have 0 or more Each operator Fns
       * that are not doing any real work (e.g. Converter, CleanupIdentityFunction)
       */
      val getForceToDiskPipeIfNecessary: Pipe = {
        val mappedPipe = rightCR(mode).toPipe(new Fields("key1", "value1"), fd, tup2Setter)
        // if the user has turned off auto force right, we fall back to the old behavior and
        //just return the mapped pipe
        if (!getHashJoinAutoForceRight || isSafeToSkipForceToDisk(mappedPipe)) mappedPipe
        else RichPipe(mappedPipe).forceToDisk
      }

      val leftPipe = leftCR(mode).toPipe(kvFields, fd, tup2Setter)

      val hashPipe = new HashJoin(
        RichPipe.assignName(leftPipe),
        Field.singleOrdered("key")(keyOrdering),
        getForceToDiskPipeIfNecessary,
        Field.singleOrdered("key1")(keyOrdering),
        WrappedJoiner(new HashJoiner(right.joinFunction, joiner)))

      CascadingPipe[(K, R)](
        hashPipe,
        kvFields,
        fd,
        tuple2Converter)
    }
  }

  private def planReduceStep[K, V1, V2](
    rsp: ReduceStepPipe[K, V1, V2],
    rec: FunctionK[TypedPipe, CurryResult]): CurryResult[(K, V2)] = {

    val rs = rsp.reduce
    val mappedCR = rec(rs.mapped)

    { mode =>

    val mapped = mappedCR(mode)

    def groupOp(gb: GroupBuilder => GroupBuilder): CascadingPipe[_ <: (K, V2)] =
      groupOpWithValueSort(None)(gb)

    def groupOpWithValueSort(valueSort: Option[Ordering[_ >: V1]])(gb: GroupBuilder => GroupBuilder): CascadingPipe[_ <: (K, V2)] = {
      val flowDef = new FlowDef
      val pipe = maybeBox[K, V1](rs.keyOrdering, flowDef) { (tupleSetter, fields) =>
        val (sortOpt, ts) = valueSort.map {
          case ordser: OrderedSerialization[V1 @unchecked] =>
            // We get in here when we do a secondary sort
            // and that sort is an ordered serialization
            // We now need a boxed serializer for this type
            // Then we set the comparator on the field, and finally we box the value with our tupleSetter
            val (boxfn, boxordSer) = getBoxFnAndOrder[V1](ordser, flowDef)
            val valueF = new Fields("value")
            valueF.setComparator("value", new CascadingBinaryComparator(boxordSer))
            val ts2 = tupleSetter.asInstanceOf[TupleSetter[(K, Boxed[V1])]].contraMap { kv1: (K, V1) => (kv1._1, boxfn(kv1._2)) }
            (Some(valueF), ts2)
          case vs =>
            val vord = Field.singleOrdered("value")(vs)
            (Some(vord), tupleSetter)
        }.getOrElse((None, tupleSetter))

        val p = mapped.toPipe(kvFields, flowDef, TupleSetter.asSubSetter(ts))

        RichPipe(p).groupBy(fields) { inGb =>
          val withSort = sortOpt.fold(inGb)(inGb.sortBy)
          gb(withSort)
        }
      }

      val tupConv = tuple2Conv[K, V2](rs.keyOrdering)
      CascadingPipe(pipe, kvFields, flowDef, tupConv)
    }

    rs match {
      case IdentityReduce(_, _, None, descriptions) =>
        // Not doing anything
        mapped.copy(pipe = RichPipe.setPipeDescriptions(mapped.pipe, descriptions)).asInstanceOf[CascadingPipe[_ <: (K, V2)]]
      case UnsortedIdentityReduce(_, _, None, descriptions) =>
        // Not doing anything
        mapped.copy(pipe = RichPipe.setPipeDescriptions(mapped.pipe, descriptions)).asInstanceOf[CascadingPipe[_ <: (K, V2)]]
      case IdentityReduce(_, _, Some(reds), descriptions) =>
        groupOp { _.reducers(reds).setDescriptions(descriptions) }
      case UnsortedIdentityReduce(_, _, Some(reds), descriptions) =>
        // This is weird, but it is sometimes used to force a partition
        groupOp { _.reducers(reds).setDescriptions(descriptions) }
      case ivsr@IdentityValueSortedReduce(_, _, _, _, _) =>
        // in this case we know that V1 =:= V2
        groupOpWithValueSort(Some(ivsr.valueSort.asInstanceOf[Ordering[_ >: V1]])) { gb =>
          // If its an ordered serialization we need to unbox
          val mappedGB =
            if (ivsr.valueSort.isInstanceOf[OrderedSerialization[_]])
              gb.mapStream[Boxed[V1], V1](valueField -> valueField) { it: Iterator[Boxed[V1]] =>
                it.map(_.get)
              }
            else
              gb

          mappedGB
            .reducers(ivsr.reducers.getOrElse(-1))
            .setDescriptions(ivsr.descriptions)
        }
      case vsr@ValueSortedReduce(_, _, _, _, _, _) =>
        val optVOrdering = Some(vsr.valueSort)
        groupOpWithValueSort(optVOrdering) {
          // If its an ordered serialization we need to unbox
          // the value before handing it to the users operation
          _.every(new cascading.pipe.Every(_, valueField,
            new TypedBufferOp[K, V1, V2](keyConverter(vsr.keyOrdering),
              valueConverter(optVOrdering),
              vsr.reduceFn,
              valueField), Fields.REPLACE))
            .reducers(vsr.reducers.getOrElse(-1))
            .setDescriptions(vsr.descriptions)
        }
      case imr@IteratorMappedReduce(_, _, _, _, _) =>
        groupOp {
          _.every(new cascading.pipe.Every(_, valueField,
            new TypedBufferOp(keyConverter(imr.keyOrdering), TupleConverter.singleConverter[V1], imr.reduceFn, valueField), Fields.REPLACE))
            .reducers(imr.reducers.getOrElse(-1))
            .setDescriptions(imr.descriptions)
        }
    }
    }
  }
}
