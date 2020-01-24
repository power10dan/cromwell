package cromwell.database.slick

import cats.instances.future._
import cats.syntax.functor._
import cromwell.database.sql.JobKeyValueSqlDatabase
import cromwell.database.sql.tables.JobKeyValueEntry

import scala.concurrent.{ExecutionContext, Future}

trait JobKeyValueSlickDatabase extends JobKeyValueSqlDatabase {
  this: EngineSlickDatabase =>

  import dataAccess.driver.api._

  override def existsJobKeyValueEntries()(implicit ec: ExecutionContext): Future[Boolean] = {
    val action = dataAccess.jobKeyValueEntriesExists.result
    runTransaction(action)
  }

  override def addJobKeyValueEntry(jobKeyValueEntry: JobKeyValueEntry)
                                  (implicit ec: ExecutionContext): Future[Unit] = {
    val action = if (useSlickUpserts) {
      for {
        _ <- dataAccess.jobKeyValueEntryIdsAutoInc.insertOrUpdate(jobKeyValueEntry)
      } yield ()
    } else manualUpsertQuery(jobKeyValueEntry)
    runTransaction(action)
  }

  private def printdbg(message: String) = {
    println(s"FINDME [$connectionDescription] $message")
  }

  private def manualUpsertQuery(jobKeyValueEntry: JobKeyValueEntry)
                               (implicit ec: ExecutionContext) = {
    for {
      updateCount <- dataAccess.
        storeValuesForJobKeyAndStoreKey((
          jobKeyValueEntry.workflowExecutionUuid,
          jobKeyValueEntry.callFullyQualifiedName,
          jobKeyValueEntry.jobIndex,
          jobKeyValueEntry.jobAttempt,
          jobKeyValueEntry.storeKey)).
        update(jobKeyValueEntry.storeValue)
      _ <- updateCount match {
        case 0 =>
          val res = dataAccess.jobKeyValueEntryIdsAutoInc += jobKeyValueEntry
          res.map { x =>
            printdbg(s"'manual' insert result for $jobKeyValueEntry = $x")
            x
          }
        case _ =>
          printdbg(s"'manual' update count for $jobKeyValueEntry = $updateCount")
          assertUpdateCount("addJobKeyValueEntry", updateCount, 1)
      }
    } yield ()
  }

  def addJobKeyValueEntries(jobKeyValueEntries: Iterable[JobKeyValueEntry])
                           (implicit ec: ExecutionContext): Future[Unit] = {
    jobKeyValueEntries.map(_.workflowExecutionUuid).toSeq.distinct.foreach { id =>
      scala.concurrent.Await.result(runTransaction(dataAccess.jobKeyValueEntriesForWorkflowExecutionUuid(id).result), scala.concurrent.duration.Duration.Inf).foreach { x =>
        printdbg(s"Row before: $x")
      }
    }
    printdbg(s"useSlickUpserts = $useSlickUpserts")
    val action = if (useSlickUpserts) {
      val toBeInserted = jobKeyValueEntries.map { jobKeyValueEntry =>
        dataAccess.jobKeyValueEntryIdsAutoInc.insertOrUpdate(jobKeyValueEntry).map { x =>
          printdbg(s"'non-manual' upsert result for $jobKeyValueEntry = $x")
          x
        }
      }
      DBIO.sequence(toBeInserted)
    } else {
      DBIO.sequence(jobKeyValueEntries.map(manualUpsertQuery))
    }
    runTransaction(action).void.andThen {
      case result =>
        printdbg(s"Result = $result")
        jobKeyValueEntries.map(_.workflowExecutionUuid).toSeq.distinct.foreach { id =>
          scala.concurrent.Await.result(runTransaction(dataAccess.jobKeyValueEntriesForWorkflowExecutionUuid(id).result), scala.concurrent.duration.Duration.Inf).foreach { x =>
            printdbg(s"Row after: $x")
          }
        }
    }
  }

  override def queryJobKeyValueEntries(workflowExecutionUuid: String)
                                      (implicit ec: ExecutionContext): Future[Seq[JobKeyValueEntry]] = {
    val action = dataAccess.jobKeyValueEntriesForWorkflowExecutionUuid(workflowExecutionUuid).result
    runTransaction(action)
  }

  override def queryStoreValue(workflowExecutionUuid: String, callFqn: String, jobScatterIndex: Int,
                               jobRetryAttempt: Int, storeKey: String)
                              (implicit ec: ExecutionContext): Future[Option[String]] = {
    val action = dataAccess.
      storeValuesForJobKeyAndStoreKey((workflowExecutionUuid, callFqn, jobScatterIndex, jobRetryAttempt, storeKey)).
      result.headOption
    runTransaction(action)
  }
}
