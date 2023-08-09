package kamon.instrumentation.pekko.remote

import java.util.concurrent.atomic.AtomicLong
import org.apache.pekko.actor.Actor
import kamon.instrumentation.pekko.PekkoClusterShardingMetrics.ShardingInstruments
import kamon.instrumentation.pekko.PekkoInstrumentation
import kamon.util.Filter
import kanela.agent.api.instrumentation.InstrumentationBuilder
import kanela.agent.libs.net.bytebuddy.asm.Advice

class ShardingInstrumentation extends InstrumentationBuilder {

    /**
      * The ShardRegion instrumentation just takes care of counting the Region messages and, when stopped, cleans up the
      * instruments and Shard sampling schedules.
      */
    onType("org.apache.pekko.cluster.sharding.ShardRegion")
      .mixin(classOf[HasShardingInstruments.Mixin])
      .advise(isConstructor, InitializeShardRegionAdvice)
      .advise(method("deliverMessage"), DeliverMessageOnShardRegion)
      .advise(method("postStop"), RegionPostStopAdvice)


    /**
      * Shards control most of metrics generated by the module and we use the internal helper methods to know when
      * entities were created or shutdown as well as measuring how many messages were sent to them through each Shard. One
      * implementation note is that messages sent to unstarted entities are going to be counted more than once because
      * they will first by buffered and then delivered when the Entity is read, we are doing this to avoid having to double
      * the work done by "deliverMessage" (like calling  he "extractEntityId" function on each message and determine
      * whether it should be buffered or forwarded).
      */
    onType("org.apache.pekko.cluster.sharding.Shard")
      .mixin(classOf[HasShardingInstruments.Mixin])
      .mixin(classOf[HasShardCounters.Mixin])
      .advise(isConstructor, InitializeShardAdvice)
      .advise(method("onLeaseAcquired"), ShardInitializedAdvice)
      .advise(method("postStop"), ShardPostStopStoppedAdvice)
      .advise(method("getOrCreateEntity"), ShardGetOrCreateEntityAdvice)
      .advise(method("entityTerminated"), ShardEntityTerminatedAdvice)
      .advise(method("org$apache$pekko$cluster$sharding$Shard$$deliverMessage"), ShardDeliverMessageAdvice)
      .advise(method("deliverMessage"), ShardDeliverMessageAdvice)

    onType("org.apache.pekko.cluster.sharding.Shard")
      .advise(method("shardInitialized"), ShardInitializedAdvice)
}


trait HasShardingInstruments {
  def shardingInstruments: ShardingInstruments
  def setShardingInstruments(shardingInstruments: ShardingInstruments): Unit
}

object HasShardingInstruments {

  class Mixin(var shardingInstruments: ShardingInstruments) extends HasShardingInstruments {
    override def setShardingInstruments(shardingInstruments: ShardingInstruments): Unit =
      this.shardingInstruments = shardingInstruments
  }
}

trait HasShardCounters {
  def hostedEntitiesCounter: AtomicLong
  def processedMessagesCounter: AtomicLong
  def setCounters(hostedEntitiesCounter: AtomicLong, processedMessagesCounter: AtomicLong): Unit
}

object HasShardCounters {

  class Mixin(var hostedEntitiesCounter: AtomicLong, var processedMessagesCounter: AtomicLong) extends HasShardCounters {
    override def setCounters(hostedEntitiesCounter: AtomicLong, processedMessagesCounter: AtomicLong): Unit = {
      this.hostedEntitiesCounter = hostedEntitiesCounter
      this.processedMessagesCounter = processedMessagesCounter
    }
  }
}

object InitializeShardRegionAdvice {

  @Advice.OnMethodExit
  def exit(@Advice.This region: Actor with HasShardingInstruments, @Advice.Argument(0) typeName: String): Unit = {
    region.setShardingInstruments(new ShardingInstruments(region.context.system.name, typeName))

    val system = region.context.system
    val shardingGuardian = system.settings.config.getString("pekko.cluster.sharding.guardian-name")
    val entitiesPath = s"${system.name}/system/$shardingGuardian/$typeName/*/*"

    PekkoInstrumentation.defineActorGroup(s"shardRegion/$typeName", Filter.fromGlob(entitiesPath))
  }
}

object InitializeShardAdvice {

  @Advice.OnMethodExit
  def exit(@Advice.This shard: Actor with HasShardingInstruments with HasShardCounters, @Advice.Argument(0) typeName: String,
      @Advice.Argument(1) shardID: String): Unit = {

    val shardingInstruments = new ShardingInstruments(shard.context.system.name, typeName)
    shard.setShardingInstruments(shardingInstruments)
    shard.setCounters(
      shardingInstruments.hostedEntitiesPerShardCounter(shardID),
      shardingInstruments.processedMessagesPerShardCounter(shardID)
    )
  }
}

object DeliverMessageOnShardRegion {

  @Advice.OnMethodEnter
  def enter(@Advice.This region: HasShardingInstruments, @Advice.Argument(0) message: Any): Unit = {
    // NOTE: The "deliverMessage" method also handles the "RestartShard" message, which is not an user-facing message
    //       but it should not happen so often so we wont do any additional matching on it to filter it out of the
    //       metric.
    region.shardingInstruments.processedMessages.increment()
  }

}

object RegionPostStopAdvice {

  @Advice.OnMethodExit
  def enter(@Advice.This shard: HasShardingInstruments): Unit =
    shard.shardingInstruments.remove()
}


object ShardInitializedAdvice {

  @Advice.OnMethodExit
  def enter(@Advice.This shard: HasShardingInstruments): Unit =
    shard.shardingInstruments.hostedShards.increment()
}

object ShardPostStopStoppedAdvice {

  @Advice.OnMethodExit
  def enter(@Advice.This shard: HasShardingInstruments): Unit =
    shard.shardingInstruments.hostedShards.decrement()
}

object ShardGetOrCreateEntityAdvice {

  @Advice.OnMethodEnter
  def enter(@Advice.This shard: Actor with HasShardingInstruments with HasShardCounters, @Advice.Argument(0) entityID: String): Unit = {
    if(shard.context.child(entityID).isEmpty) {
      // The entity is not created just yet, but we know that it will be created right after this.
      shard.shardingInstruments.hostedEntities.increment()
      shard.hostedEntitiesCounter.incrementAndGet()
    }
  }
}

object ShardEntityTerminatedAdvice {

  @Advice.OnMethodEnter
  def enter(@Advice.This shard: Actor with HasShardingInstruments with HasShardCounters): Unit = {
    shard.shardingInstruments.hostedEntities.decrement()
    shard.hostedEntitiesCounter.decrementAndGet()
  }
}

object ShardDeliverMessageAdvice {
  @Advice.OnMethodEnter
  def enter(@Advice.This shard: Actor with HasShardingInstruments with HasShardCounters): Unit = {
    shard.processedMessagesCounter.incrementAndGet()
  }

}
