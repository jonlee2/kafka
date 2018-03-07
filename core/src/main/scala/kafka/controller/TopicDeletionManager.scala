/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.controller


import kafka.common.TopicAndPartition
import kafka.server.ConfigType
import kafka.utils.Logging
import kafka.utils.ZkUtils._

import scala.collection.{Set, mutable}

/**
 * This manages the state machine for topic deletion.
 * 1. TopicCommand issues topic deletion by creating a new admin path /admin/delete_topics/<topic>
 * 2. The controller listens for child changes on /admin/delete_topic and starts topic deletion for the respective topics
 * 3. The controller's ControllerEventThread handles topic deletion. A topic will be ineligible
 *    for deletion in the following scenarios -
  *   3.1 broker hosting one of the replicas for that topic goes down
  *   3.2 partition reassignment for partitions of that topic is in progress
 * 4. Topic deletion is resumed when -
 *    4.1 broker hosting one of the replicas for that topic is started
 *    4.2 partition reassignment for partitions of that topic completes
 * 5. Every replica for a topic being deleted is in either of the 3 states -
 *    5.1 TopicDeletionStarted Replica enters TopicDeletionStarted phase when onPartitionDeletion is invoked.
 *        This happens when the child change watch for /admin/delete_topics fires on the controller. As part of this state
 *        change, the controller sends StopReplicaRequests to all replicas. It registers a callback for the
 *        StopReplicaResponse when deletePartition=true thereby invoking a callback when a response for delete replica
 *        is received from every replica)
 *    5.2 TopicDeletionSuccessful moves replicas from
 *        TopicDeletionStarted->TopicDeletionSuccessful depending on the error codes in StopReplicaResponse
 *    5.3 TopicDeletionFailed moves replicas from
 *        TopicDeletionStarted->TopicDeletionFailed depending on the error codes in StopReplicaResponse.
 *        In general, if a broker dies and if it hosted replicas for topics being deleted, the controller marks the
 *        respective replicas in TopicDeletionFailed state in the onBrokerFailure callback. The reason is that if a
 *        broker fails before the request is sent and after the replica is in TopicDeletionStarted state,
 *        it is possible that the replica will mistakenly remain in TopicDeletionStarted state and topic deletion
 *        will not be retried when the broker comes back up.
 * 6. A topic is marked successfully deleted only if all replicas are in TopicDeletionSuccessful
 *    state. Topic deletion teardown mode deletes all topic state from the controllerContext
 *    as well as from zookeeper. This is the only time the /brokers/topics/<topic> path gets deleted. On the other hand,
 *    if no replica is in TopicDeletionStarted state and at least one replica is in TopicDeletionFailed state, then
 *    it marks the topic for deletion retry.
 * @param controller
 */
class TopicDeletionManager(controller: KafkaController, eventManager: ControllerEventManager) extends Logging {
  this.logIdent = "[Topic Deletion Manager " + controller.config.brokerId + "], "
  val controllerContext = controller.controllerContext
  val partitionStateMachine = controller.partitionStateMachine
  val replicaStateMachine = controller.replicaStateMachine
  val isDeleteTopicEnabled = controller.config.deleteTopicEnable
  val topicsToBeDeleted = mutable.Set.empty[TopicToBeDeleted]
  val partitionsToBeDeleted = mutable.Set.empty[TopicAndPartition]
  val topicsIneligibleForDeletion = mutable.Set.empty[String]

  def init(initialTopicsToBeDeleted: Set[TopicToBeDeleted], initialTopicsIneligibleForDeletion: Set[String]): Unit = {
    if (isDeleteTopicEnabled) {
      topicsToBeDeleted ++= initialTopicsToBeDeleted
      partitionsToBeDeleted ++= topicsToBeDeleted.map(_.topicName).flatMap(controllerContext.partitionsForTopic)
      topicsIneligibleForDeletion ++= initialTopicsIneligibleForDeletion & topicsToBeDeleted.map(_.topicName)
    } else {
      // if delete topic is disabled clean the topic entries under /admin/delete_topics
      val zkUtils = controllerContext.zkUtils
      for (topic <- initialTopicsToBeDeleted) {
        val deleteTopicPath = getDeleteTopicPath(topic.topicName)
        info("Removing " + deleteTopicPath + " since delete topic is disabled")
        zkUtils.deletePath(deleteTopicPath)
      }
    }
  }

  def tryTopicDeletion(): Unit = {
    if (isDeleteTopicEnabled) {
      resumeDeletions()
    }
  }

  /**
   * Invoked when the current controller resigns. At this time, all state for topic deletion should be cleared.
   */
  def reset() {
    if (isDeleteTopicEnabled) {
      topicsToBeDeleted.clear()
      partitionsToBeDeleted.clear()
      topicsIneligibleForDeletion.clear()
    }
  }

  /**
   * Invoked by the child change listener on /admin/delete_topics to queue up the topics for deletion. The topic gets added
   * to the topicsToBeDeleted list and only gets removed from the list when the topic deletion has completed successfully
   * i.e. all replicas of all partitions of that topic are deleted successfully.
   * @param topics Topics that should be deleted
   */
  def enqueueTopicsForDeletion(topics: Set[TopicToBeDeleted]) {
    if(isDeleteTopicEnabled) {
      topicsToBeDeleted ++= topics
      partitionsToBeDeleted ++= topics.map(_.topicName).flatMap(controllerContext.partitionsForTopic)
      resumeDeletions()
    }
  }

  /**
   * Invoked when any event that can possibly resume topic deletion occurs. These events include -
   * 1. New broker starts up. Any replicas belonging to topics queued up for deletion can be deleted since the broker is up
   * 2. Partition reassignment completes. Any partitions belonging to topics queued up for deletion finished reassignment
   * @param topics Topics for which deletion can be resumed
   */
  def resumeDeletionForTopics(topics: Set[String] = Set.empty) {
    if(isDeleteTopicEnabled) {
      val topicsToResumeDeletion = topics & topicsToBeDeleted.map(_.topicName)
      if(topicsToResumeDeletion.nonEmpty) {
        topicsIneligibleForDeletion --= topicsToResumeDeletion
        resumeDeletions()
      }
    }
  }

  /**
   * Invoked when a broker that hosts replicas for topics to be deleted goes down. Also invoked when the callback for
   * StopReplicaResponse receives an error code for the replicas of a topic to be deleted. As part of this, the replicas
   * are moved from ReplicaDeletionStarted to ReplicaDeletionIneligible state. Also, the topic is added to the list of topics
   * ineligible for deletion until further notice.
   * @param replicas Replicas for which deletion has failed
   */
  def failReplicaDeletion(replicas: Set[PartitionAndReplica]) {
    if(isDeleteTopicEnabled) {
      val replicasThatFailedToDelete = replicas.filter(r => isTopicQueuedUpForDeletion(r.topic))
      if(replicasThatFailedToDelete.nonEmpty) {
        val topics = replicasThatFailedToDelete.map(_.topic)
        debug("Deletion failed for replicas %s. Halting deletion for topics %s"
          .format(replicasThatFailedToDelete.mkString(","), topics))
        controller.replicaStateMachine.handleStateChanges(replicasThatFailedToDelete, ReplicaDeletionIneligible)
        markTopicIneligibleForDeletion(topics)
        resumeDeletions()
      }
    }
  }

  /**
   * Halt delete topic if -
   * 1. replicas being down
   * 2. partition reassignment in progress for some partitions of the topic
   * @param topics Topics that should be marked ineligible for deletion. No op if the topic is was not previously queued up for deletion
   */
  def markTopicIneligibleForDeletion(topics: Set[String]) {
    if(isDeleteTopicEnabled) {
      val newTopicsToHaltDeletion = topicsToBeDeleted.map(_.topicName) & topics
      topicsIneligibleForDeletion ++= newTopicsToHaltDeletion
      if(newTopicsToHaltDeletion.nonEmpty)
        info("Halted deletion of topics %s".format(newTopicsToHaltDeletion.mkString(",")))
    }
  }

  private def isTopicIneligibleForDeletion(topic: String): Boolean = {
    if(isDeleteTopicEnabled) {
      topicsIneligibleForDeletion.contains(topic)
    } else
      true
  }

  private def isTopicDeletionInProgress(topic: String): Boolean = {
    if(isDeleteTopicEnabled) {
      controller.replicaStateMachine.isAtLeastOneReplicaInDeletionStartedState(topic)
    } else
      false
  }

  def isPartitionToBeDeleted(topicAndPartition: TopicAndPartition) = {
    if(isDeleteTopicEnabled) {
      partitionsToBeDeleted.contains(topicAndPartition)
    } else
      false
  }

  def isTopicQueuedUpForDeletion(topic: String): Boolean = {
    if(isDeleteTopicEnabled) {
      topicsToBeDeleted.map(_.topicName).contains(topic)
    } else
      false
  }

  /**
   * Invoked by the StopReplicaResponse callback when it receives no error code for a replica of a topic to be deleted.
   * As part of this, the replicas are moved from ReplicaDeletionStarted to ReplicaDeletionSuccessful state. Tears down
   * the topic if all replicas of a topic have been successfully deleted
   * @param replicas Replicas that were successfully deleted by the broker
   */
  def completeReplicaDeletion(replicas: Set[PartitionAndReplica]) {
    val successfullyDeletedReplicas = replicas.filter(r => isTopicQueuedUpForDeletion(r.topic))
    debug("Deletion successfully completed for replicas %s".format(successfullyDeletedReplicas.mkString(",")))
    controller.replicaStateMachine.handleStateChanges(successfullyDeletedReplicas, ReplicaDeletionSuccessful)
    resumeDeletions()
  }

  /**
   * Topic deletion can be retried if -
   * 1. Topic deletion is not already complete
   * 2. Topic deletion is currently not in progress for that topic
   * 3. Topic is currently marked ineligible for deletion
   * @param topic Topic
   * @return Whether or not deletion can be retried for the topic
   */
  private def isTopicEligibleForDeletion(topic: String): Boolean = {
    topicsToBeDeleted.map(_.topicName).contains(topic) && (!isTopicDeletionInProgress(topic) && !isTopicIneligibleForDeletion(topic))
  }

  /**
   * If the topic is queued for deletion but deletion is not currently under progress, then deletion is retried for that topic
   * To ensure a successful retry, reset states for respective replicas from ReplicaDeletionIneligible to OfflineReplica state
   *@param topic Topic for which deletion should be retried
   */
  private def markTopicForDeletionRetry(topic: String) {
    // reset replica states from ReplicaDeletionIneligible to OfflineReplica
    val failedReplicas = controller.replicaStateMachine.replicasInState(topic, ReplicaDeletionIneligible)
    info("Retrying delete topic for topic %s since replicas %s were not successfully deleted"
      .format(topic, failedReplicas.mkString(",")))
    controller.replicaStateMachine.handleStateChanges(failedReplicas, OfflineReplica)
  }

  private def completeDeleteTopic(topic: String) {
    // deregister partition change listener on the deleted topic. This is to prevent the partition change listener
    // firing before the new topic listener when a deleted topic gets auto created
    controller.deregisterPartitionModificationsListener(topic)
    val replicasForDeletedTopic = controller.replicaStateMachine.replicasInState(topic, ReplicaDeletionSuccessful)
    // controller will remove this replica from the state machine as well as its partition assignment cache
    replicaStateMachine.handleStateChanges(replicasForDeletedTopic, NonExistentReplica)
    val partitionsForDeletedTopic = controllerContext.partitionsForTopic(topic)
    // move respective partition to OfflinePartition and NonExistentPartition state
    partitionStateMachine.handleStateChanges(partitionsForDeletedTopic, OfflinePartition)
    partitionStateMachine.handleStateChanges(partitionsForDeletedTopic, NonExistentPartition)
    topicsToBeDeleted.retain(_.topicName != topic)
    partitionsToBeDeleted.retain(_.topic != topic)
    val zkUtils = controllerContext.zkUtils
    zkUtils.deletePathRecursive(getTopicPath(topic))
    zkUtils.deletePathRecursive(getEntityConfigPath(ConfigType.Topic, topic))
    zkUtils.deletePath(getDeleteTopicPath(topic))
    controllerContext.removeTopic(topic)

    // the topic with earliest timestamp has been successfully deleted, now resume deletion for the next topic
    resumeDeletions()
  }

  /**
   * Invoked with the list of topics to be deleted
   * It invokes onPartitionDeletion for all partitions of a topic.
   * The updateMetadataRequest is also going to set the leader for the topics being deleted to
   * {@link LeaderAndIsr#LeaderDuringDelete}. This lets each broker know that this topic is being deleted and can be
   * removed from their caches.
   */
  private def onTopicDeletion(topics: Set[String]) {
    info("Topic deletion callback for %s".format(topics.mkString(",")))
    // send update metadata so that brokers stop serving data for topics to be deleted
    val partitions = topics.flatMap(controllerContext.partitionsForTopic)
    controller.sendUpdateMetadataRequest(controllerContext.liveOrShuttingDownBrokerIds.toSeq, partitions)
    topics.foreach { topic =>
      onPartitionDeletion(controllerContext.partitionsForTopic(topic))
    }
  }

  /**
   * Invoked by onPartitionDeletion. It is the 2nd step of topic deletion, the first being sending
   * UpdateMetadata requests to all brokers to start rejecting requests for deleted topics. As part of starting deletion,
   * the topics are added to the in progress list. As long as a topic is in the in progress list, deletion for that topic
   * is never retried. A topic is removed from the in progress list when
   * 1. Either the topic is successfully deleted OR
   * 2. No replica for the topic is in ReplicaDeletionStarted state and at least one replica is in ReplicaDeletionIneligible state
   * If the topic is queued for deletion but deletion is not currently under progress, then deletion is retried for that topic
   * As part of starting deletion, all replicas are moved to the ReplicaDeletionStarted state where the controller sends
   * the replicas a StopReplicaRequest (delete=true)
   * This method does the following things -
   * 1. Move all dead replicas directly to ReplicaDeletionIneligible state. Also mark the respective topics ineligible
   *    for deletion if some replicas are dead since it won't complete successfully anyway
   * 2. Move all alive replicas to ReplicaDeletionStarted state so they can be deleted successfully
   *@param replicasForTopicsToBeDeleted
   */
  private def startReplicaDeletion(replicasForTopicsToBeDeleted: Set[PartitionAndReplica]) {
    replicasForTopicsToBeDeleted.groupBy(_.topic).keys.foreach { topic =>
      val aliveReplicasForTopic = controllerContext.allLiveReplicas().filter(p => p.topic == topic)
      val deadReplicasForTopic = replicasForTopicsToBeDeleted -- aliveReplicasForTopic
      val successfullyDeletedReplicas = controller.replicaStateMachine.replicasInState(topic, ReplicaDeletionSuccessful)
      val replicasForDeletionRetry = aliveReplicasForTopic -- successfullyDeletedReplicas
      // move dead replicas directly to failed state
      replicaStateMachine.handleStateChanges(deadReplicasForTopic, ReplicaDeletionIneligible)
      // send stop replica to all followers that are not in the OfflineReplica state so they stop sending fetch requests to the leader
      replicaStateMachine.handleStateChanges(replicasForDeletionRetry, OfflineReplica)
      debug("Deletion started for replicas %s".format(replicasForDeletionRetry.mkString(",")))
      controller.replicaStateMachine.handleStateChanges(replicasForDeletionRetry, ReplicaDeletionStarted,
        new Callbacks.CallbackBuilder().stopReplicaCallback((stopReplicaResponseObj, replicaId) =>
          eventManager.put(controller.TopicDeletionStopReplicaResponseReceived(stopReplicaResponseObj, replicaId))).build)
      if (deadReplicasForTopic.nonEmpty) {
        debug("Dead Replicas (%s) found for topic %s".format(deadReplicasForTopic.mkString(","), topic))
        markTopicIneligibleForDeletion(Set(topic))
      }
    }
  }

  /**
   * Invoked by onTopicDeletion with the list of partitions for topics to be deleted
   * It does the following -
   * 1. Send UpdateMetadataRequest to all live brokers (that are not shutting down) for partitions that are being
   *    deleted. The brokers start rejecting all client requests with UnknownTopicOrPartitionException
   * 2. Move all replicas for the partitions to OfflineReplica state. This will send StopReplicaRequest to the replicas
   *    and LeaderAndIsrRequest to the leader with the shrunk ISR. When the leader replica itself is moved to OfflineReplica state,
   *    it will skip sending the LeaderAndIsrRequest since the leader will be updated to -1
   * 3. Move all replicas to ReplicaDeletionStarted state. This will send StopReplicaRequest with deletePartition=true. And
   *    will delete all persistent data from all replicas of the respective partitions
   */
  private def onPartitionDeletion(partitionsToBeDeleted: Set[TopicAndPartition]) {
    info("Partition deletion callback for %s".format(partitionsToBeDeleted.mkString(",")))
    val replicasPerPartition = controllerContext.replicasForPartition(partitionsToBeDeleted)
    startReplicaDeletion(replicasPerPartition)
  }

  private def chooseTopicWithEarliestDeletionTime() : String = {
    topicsToBeDeleted.toSeq.sortBy(_.deletionEnqueueTime).head.topicName
  }

  private def resumeDeletions(): Unit = {
    /**
      * we only allow one topic deletion to be in progress,
      * so we'll always try to resume deletion for the topic with earliest deletion timestamp
      */
    if (topicsToBeDeleted.nonEmpty) {
      val topic = chooseTopicWithEarliestDeletionTime()
      info(s"Handling deletion for topic $topic")

      // if all replicas are marked as deleted successfully, then topic deletion is done
      if(controller.replicaStateMachine.areAllReplicasForTopicDeleted(topic)) {
        // clear up all state for this topic from controller cache and zookeeper
        completeDeleteTopic(topic)
        info("Deletion of topic %s successfully completed".format(topic))
      } else {
        if(controller.replicaStateMachine.isAtLeastOneReplicaInDeletionStartedState(topic)) {
          // ignore since topic deletion is in progress
          val replicasInDeletionStartedState = controller.replicaStateMachine.replicasInState(topic, ReplicaDeletionStarted)
          val replicaIds = replicasInDeletionStartedState.map(_.replica)
          val partitions = replicasInDeletionStartedState.map(r => TopicAndPartition(r.topic, r.partition))
          info("Deletion for replicas %s for partition %s of topic %s in progress".format(replicaIds.mkString(","),
            partitions.mkString(","), topic))
        } else {
          // if you come here, then no replica is in TopicDeletionStarted and all replicas are not in
          // TopicDeletionSuccessful. That means, that either given topic haven't initiated deletion
          // or there is at least one failed replica (which means topic deletion should be retried).
          if(controller.replicaStateMachine.isAnyReplicaInState(topic, ReplicaDeletionIneligible)) {
            // mark topic for deletion retry
            markTopicForDeletionRetry(topic)
          }
        }
      }
      // Try delete topic if it is eligible for deletion.
      if(isTopicEligibleForDeletion(topic)) {
        info("Deletion of topic %s (re)started".format(topic))
        // topic deletion will be kicked off
        onTopicDeletion(Set(topic))
      } else if(isTopicIneligibleForDeletion(topic)) {
        info("Not retrying deletion of topic %s at this time since it is marked ineligible for deletion".format(topic))
      }
    }
  }
}

/**
  * An TopicToBeDeleted object stores a topic to be deleted and the topic deletion time, i.e. the ctime of znode /admin/delete_topics/topic
  * The deletion time is used to ensure topic deletions happen sequentially in increasing deletion time
  * that is, deletion of a topic does not start until all topics with earlier deletion times are successfully deleted
  * @param topicName The topic name
  * @param deletionEnqueueTime The deletion time defined as ctime of the znode at /admin/delete_topics/topic
  */
case class TopicToBeDeleted (topicName : String, deletionEnqueueTime : Long)

