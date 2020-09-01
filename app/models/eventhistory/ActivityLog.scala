package models.eventhistory

import java.time.LocalDateTime
import java.sql.Connection
import java.time.format.DateTimeFormatter

import play.api.libs.json.{Json, OFormat}
import play.api.Logging

import models.input.SearchInputWithRules
import models.rules._
import models.spellings.{AlternativeSpelling, CanonicalSpellingWithAlternatives}
import models.SolrIndexId

/**
  *
  * @param entity
  * @param eventType
  * @param before
  * @param after
  *
  * examples below (for being displayed on the frontend):
  *
  * |  entity/eventType    |  before                 |  after              |  user info            |
  * |  ~~~~~~~~~~~~~~~~    |  ~~~~~~                 |  ~~~~~              |  ~~~~~~~~~            |
  * |  INPUT (created)     |                         |  laptop (active)    |  Paul Search Manager  |
  * |  RULE (created)      |                         |  netbook            |  Paul Search Manager  |
  * |  RULE (updated)      |  -notebück (inactive)-  |  notebook (active)  |  Paul Search Manager  |
  * |  RULE (deleted)      |  -lapptopp-             |                     |  Paul Search Manager  |
  * |  SPELL. (created)    |                         |  lapptopp (active)  |  Paul Search Manager  |
  * |  COMM. (updated)     |  -Comment before-       |  Comment after      |  Paul Search Manager  |
  * |  LIVE DEPLOY         |                         |  Status: OK         |  Paul Search Manager  |
  * |  PRELIVE DEPLOY      |                         |  Status: FAIL       |  Paul Search Manager  |
  */
case class DiffSummary(
  entity: String,
  eventType: String,
  before: Option[String],
  after: Option[String]
)

object DiffSummary {

  object HEADLINE extends Enumeration {
    val INPUT = "INPUT"
    val RULE = "RULE"
    val SPELLING = "SPELLING"
    val COMMENT = "COMMENT"
    val ALT_SPELLING = "MISSPELLING"
  }

  def readableEventType(eventType: SmuiEventType.Value): String = {
    eventType match {
      case SmuiEventType.CREATED
        | SmuiEventType.VIRTUALLY_CREATED => "created"
      case SmuiEventType.UPDATED => "updated"
      case SmuiEventType.DELETED => "deleted"
      case _ => "unknown event" // TODO maybe throw an exception?
    }
  }

}

case class ActivityLogEntry(
  // TODO be homogeneous in delivering a date as raw LocalDateTime (see /smui/app/models/reports/RulesReport.scala) from backend - the date should be converted on the frontend
  formattedDateTime: String,
  userInfo: Option[String],
  diffSummary: Seq[DiffSummary]
)

case class ActivityLog(items: Seq[ActivityLogEntry])

object ActivityLog extends Logging {

  implicit val jsonFormatDiffSummary: OFormat[DiffSummary] = Json.format[DiffSummary]
  implicit val jsonFormatActivityLogEntry: OFormat[ActivityLogEntry] = Json.format[ActivityLogEntry]
  implicit val jsonFormatActivityLog: OFormat[ActivityLog] = Json.format[ActivityLog]

  /*
   * activity/diff ecosystem of SearchInputWithRules
   */

  private def readableStatus(isActive: Boolean): String = {
    if (isActive) "activated" else "deactivated"
  }

  private def readableTermStatus(term: String, status: Boolean): String = {
    term + " (" + readableStatus(status) + ")"
  }

  private def diffTermStatus(entity: String, beforeTerm: String, beforeStatus: Boolean, afterTerm: String, afterStatus: Boolean): Option[DiffSummary] = {

    val termDiff = if (beforeTerm.trim.equals(afterTerm.trim)) None else Some(afterTerm.trim)
    val statDiff = if (beforeStatus.equals(afterStatus)) None else Some(afterStatus)

    if (termDiff.isDefined || statDiff.isDefined) {

      val beforeAfter = (if (termDiff.isDefined && statDiff.isDefined) {
        (readableTermStatus(beforeTerm.trim, beforeStatus), readableTermStatus(afterTerm.trim, afterStatus))
      } else if (termDiff.isDefined) {
        (beforeTerm.trim, afterTerm.trim)
      } else { // (statDiff.isDefined)
        (readableTermStatus(beforeTerm.trim, beforeStatus), readableTermStatus(afterTerm.trim, afterStatus))
      })

      Some(
        DiffSummary(
          entity = entity,
          eventType = DiffSummary.readableEventType(SmuiEventType.UPDATED),
          before = Some(beforeAfter._1),
          after = Some(beforeAfter._2)
        )
      )
    }
    else {
      None
    }
  }

  /**
    * Helper classes to deal with SearchInput and CanonicalSpelling the same way
    */
  // TODO consider defining a common Input/Association interface

  private class AssociationWrapper(association: Any) {

    def headline = association match {
      case _: Rule => DiffSummary.HEADLINE.RULE
      case _: AlternativeSpelling => DiffSummary.HEADLINE.ALT_SPELLING
    }

    def id = association match {
      case rule: Rule => rule.id.id
      case altSpelling: AlternativeSpelling => altSpelling.id.id
    }

    def trimmedTerm = association match {
      case ruleWithTerm: RuleWithTerm => ruleWithTerm.term.trim
      case redirectRule: RedirectRule => s"URL: ${redirectRule.target.trim}"
      case altSpelling: AlternativeSpelling => altSpelling.term.trim
    }

    def isActive = association match {
      case rule: Rule => rule.isActive
      case altSpelling: AlternativeSpelling => altSpelling.isActive
    }

  }

  private class InputWrapper(inputEvent: InputEvent) {

    val input: Any =
      inputEvent.eventSource match {
        case SmuiEventSource.SEARCH_INPUT => {
          // TODO log error in case JSON read validation fails
          Json.parse(inputEvent.jsonPayload.get).validate[SearchInputWithRules].asOpt.get
        }
        case SmuiEventSource.SPELLING => {
          // TODO log error in case JSON read validation fails
          Json.parse(inputEvent.jsonPayload.get).validate[CanonicalSpellingWithAlternatives].asOpt.get
        }
        // case _ => logger.error(s"Unexpected eventSource (${beforeEvent.eventSource}) in event with id = ${beforeEvent.id}")
      }

    def headline = input match {
      case searchInput: SearchInputWithRules => DiffSummary.HEADLINE.INPUT
      case spelling: CanonicalSpellingWithAlternatives => DiffSummary.HEADLINE.SPELLING
    }

    def trimmedTerm = input match {
      case searchInput: SearchInputWithRules => searchInput.trimmedTerm
      case spelling: CanonicalSpellingWithAlternatives => spelling.term.trim
    }

    def isActive = input match {
      case searchInput: SearchInputWithRules => searchInput.isActive
      case spelling: CanonicalSpellingWithAlternatives => spelling.isActive
    }

    val associations = input match {
      case searchInput: SearchInputWithRules => searchInput.allRules.map(r => new AssociationWrapper(r))
      case spelling: CanonicalSpellingWithAlternatives => spelling.alternativeSpellings.map(s => new AssociationWrapper(s))
    }

    def trimmedComment = input match {
      case searchInput: SearchInputWithRules => searchInput.comment.trim
      case spelling: CanonicalSpellingWithAlternatives => spelling.comment.trim
    }

  }

  private def outputBeforeEvent(wrappedBefore: InputWrapper, outputEventType: SmuiEventType.Value, beforeNotAfter: Boolean) = {
    // output input

    val iSummaryValue = readableTermStatus(
      wrappedBefore.trimmedTerm,
      wrappedBefore.isActive
    )
    val inputSummary = List(
      DiffSummary(
        entity = wrappedBefore.headline,
        eventType = DiffSummary.readableEventType(outputEventType),
        before = if(beforeNotAfter) Some(iSummaryValue) else None,
        after = if(beforeNotAfter) None else Some(iSummaryValue)
      )
    )

    // output associations (rules/spellings)

    val assocsSummary = wrappedBefore.associations
      .map(a => {
        val aSummaryValue = readableTermStatus(
          a.trimmedTerm,
          a.isActive
        )
        DiffSummary(
          entity = a.headline,
          eventType = DiffSummary.readableEventType(outputEventType),
          before = if(beforeNotAfter) Some(aSummaryValue) else None,
          after = if(beforeNotAfter) None else Some(aSummaryValue)
        )
      })

    // output comment

    val commSummary = List(
      DiffSummary(
        entity = DiffSummary.HEADLINE.COMMENT,
        eventType = DiffSummary.readableEventType(outputEventType),
        before = if(beforeNotAfter) Some(wrappedBefore.trimmedComment) else None,
        after = if(beforeNotAfter) None else Some(wrappedBefore.trimmedComment)
      )
    )

    // return concatenated

    inputSummary ++
    assocsSummary ++
    commSummary
  }

  private def outputDiffAssociations(beforeAssociations: Seq[AssociationWrapper], afterAssociations: Seq[AssociationWrapper]) = {

    // determine CREATED/DELETED and potential UPDATED rules

    val intersectIds = beforeAssociations.map(_.id).intersect(afterAssociations.map(_.id))
    val assocsCreated = afterAssociations.filter(a => !intersectIds.contains(a.id))
    val assocsDeleted = beforeAssociations.filter(a => !intersectIds.contains(a.id))
    val assocsMaybeUpdated = beforeAssociations.filter(a => intersectIds.contains(a.id))

    // generate summaries for CREATED rules

    val createdSummaries = assocsCreated.map(a =>
      DiffSummary(
        entity = DiffSummary.HEADLINE.RULE,
        eventType = DiffSummary.readableEventType(SmuiEventType.CREATED),
        before = None,
        after = Some(readableTermStatus(a.trimmedTerm, a.isActive))
      )
    )

    // generate summaries for DELETED rules

    val deletedSummaries = assocsDeleted.map(a =>
      DiffSummary(
        entity = DiffSummary.HEADLINE.RULE,
        eventType = DiffSummary.readableEventType(SmuiEventType.DELETED),
        before = Some(readableTermStatus(a.trimmedTerm, a.isActive)),
        after = None
      )
    )

    // determine real UPDATED rules and generate summaries

    val updatedSummaries = assocsMaybeUpdated.map(beforeAssoc => {
      val afterAssoc = afterAssociations.filter(p => p.id.equals(beforeAssoc.id)).head
      diffTermStatus(
        DiffSummary.HEADLINE.RULE,
        beforeAssoc.trimmedTerm, beforeAssoc.isActive,
        afterAssoc.trimmedTerm, afterAssoc.isActive
      )
    })
      .filter(d => d.isDefined)
      .map(o => o.get)

    createdSummaries ++
    deletedSummaries ++
    updatedSummaries
  }

  private def outputDiff(wrappedBefore: InputWrapper, wrappedAfter: InputWrapper) = {

    // diff & output input

    val inputDiff = diffTermStatus(
      DiffSummary.HEADLINE.INPUT,
      wrappedBefore.trimmedTerm, wrappedBefore.isActive,
      wrappedAfter.trimmedTerm, wrappedAfter.isActive
    ) match {
      case Some(d: DiffSummary) => List(d)
      case None => Nil
    }

    // diff & output associations (rules/spellings)

    val rulesDiff = outputDiffAssociations(wrappedBefore.associations, wrappedAfter.associations)

    // diff & output comment

    val commDiff = (if (wrappedBefore.trimmedComment.equals(wrappedAfter.trimmedComment))
      Nil
    else
      List(
        DiffSummary(
          entity = DiffSummary.HEADLINE.COMMENT,
          eventType = DiffSummary.readableEventType(SmuiEventType.UPDATED),
          before = Some(wrappedBefore.trimmedComment),
          after = Some(wrappedAfter.trimmedComment)
        )
      )
    )

    // return concatenated

    inputDiff ++
    rulesDiff ++
    commDiff
  }

  /**
    * Generate an ActivityLogEntry for two sequential events.
    *
    * @param beforeEvent
    * @param afterEvent
    * @return
    */
  private def processInputEvents(beforeEvent: Option[InputEvent], afterEvent: InputEvent): ActivityLogEntry = {

    // support the following valid event constellations:
    // before -> after    | compare activity
    // ~~~~~~~~~~~~~~~~~~ | ~~~~~~~~~~~~~~~~
    // None    -> CREATED | event, output all contents of beforeEvent as after, before = empty
    // CREATED -> DELETED | events, output all contents of beforeEvent as before, after = empty
    // CREATED -> UPDATED | events, output diff of before/after
    // UPDATED -> UPDATED | events, output diff of before/after
    // UPDATED -> DELETED | events, output all contents of beforeEvent as before, after = empty
    // (important: CREATED and VIRTUALLY_CREATED are equal in that context)

    val beforeEventType = beforeEvent match {
      case None => None
      case Some(e) => Some(SmuiEventType.toSmuiEventType(e.eventType))
    }

    val USER_INFO_MIGRATION = "SMUI system (pre v3.8 migration)"

    def virtualUserInfo(eventUserInfo: Option[String], rawEventType: Int) = {
      eventUserInfo match {
        case None => {
          if(SmuiEventType.toSmuiEventType(rawEventType).equals(SmuiEventType.VIRTUALLY_CREATED))
            Some(USER_INFO_MIGRATION)
          else
            None
        }
        case Some(i) => Some(i)
      }
    }

    val wrappedAfter = new InputWrapper(afterEvent)

    (beforeEventType, SmuiEventType.toSmuiEventType(afterEvent.eventType)) match {
      case (None, SmuiEventType.CREATED)
        | (None, SmuiEventType.VIRTUALLY_CREATED) => {

        ActivityLogEntry(
          formattedDateTime = afterEvent.eventTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
          userInfo = virtualUserInfo(afterEvent.userInfo, afterEvent.eventType),
          diffSummary = outputBeforeEvent(wrappedAfter, SmuiEventType.CREATED, false)
        )

      }
      case (Some(SmuiEventType.CREATED), SmuiEventType.DELETED)
        | (Some(SmuiEventType.VIRTUALLY_CREATED), SmuiEventType.DELETED)
        | (Some(SmuiEventType.UPDATED), SmuiEventType.DELETED) => {

        val wrappedBefore = new InputWrapper(beforeEvent.get)

        ActivityLogEntry(
          formattedDateTime = afterEvent.eventTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
          userInfo = virtualUserInfo(afterEvent.userInfo, afterEvent.eventType),
          diffSummary = outputBeforeEvent(wrappedAfter, SmuiEventType.DELETED, true)
        )

      }
      case (Some(SmuiEventType.CREATED), SmuiEventType.UPDATED)
        | (Some(SmuiEventType.VIRTUALLY_CREATED), SmuiEventType.UPDATED)
        | (Some(SmuiEventType.UPDATED), SmuiEventType.UPDATED) => {

        val wrappedBefore = new InputWrapper(beforeEvent.get)

        ActivityLogEntry(
          formattedDateTime = afterEvent.eventTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
          userInfo = virtualUserInfo(afterEvent.userInfo, afterEvent.eventType),
          diffSummary = outputDiff(wrappedBefore, wrappedAfter)
        )

      }
      case _ => {
        logger.error(s"IllegalState: processInputEvents found event chain ($beforeEventType, ${afterEvent.eventType})")
        // TODO maybe throw IllegalState exception instead
        ActivityLogEntry(
          formattedDateTime = "error (see log)",
          userInfo = None,
          diffSummary = Nil
        )
      }
    }
  }

  /**
    * Interface
    */

  def loadForId(id: String)(implicit connection: Connection): ActivityLog = {

    // get all persisted events for id
    val events = InputEvent.loadForId(id)
    if (events.isEmpty) {
      // TODO if there is not even one first CREATED event, virtually create one and reload events
      // TODO that should have been done with migration (/smui/app/models/eventhistory/MigrationService.scala)
      return ActivityLog(Nil)
    }
    else {

      // create new list of pairwise events
      // (important: 1st element must be an Option)

      val allEvents = (List(None) ++ events.map(e => Some(e)))
      val pairwiseEvents =
        (allEvents zip allEvents.tail)
        .map(eventPair =>
          (eventPair._1, eventPair._2)
        )

      // pairwise compare and map diffs to ActivityLog entries

      val activityLogItems = pairwiseEvents.map( eventPair => {
        processInputEvents(eventPair._1, eventPair._2.get)
      })

      ActivityLog(
        items = activityLogItems
          .reverse
          .filter(entry => !entry.diffSummary.isEmpty)
      )
    }
  }

  def reportForSolrIndexIdInPeriod(solrIndexId: SolrIndexId, dateFrom: LocalDateTime, dateTo: LocalDateTime)(implicit connection: Connection): ActivityLog = {

    val changedIds = InputEvent.allChangedInputIdsForSolrIndexIdInPeriod(solrIndexId, dateFrom, dateTo)

    logger.info(s":: changedIds.size = ${changedIds.size}")

    if(changedIds.isEmpty) {
      ActivityLog(
        items = Nil
      )
    } else {

      // load all corresponding activity log entries for the period

      val activityLogItems: List[ActivityLogEntry] = changedIds.map(id => {
        val (maybeBefore, maybeAfter) = InputEvent.changeEventsForIdInPeriod(id, dateFrom, dateTo)
        // TODO UX: point to input/rule/spelling in "Entity event type"
        // TODO add error for (None, None)
        processInputEvents(maybeBefore, maybeAfter.get)
      })

      // TODO explicit sorting, e.g.: sorted by event date of input
      // ^--> formattedDateTime = afterEvent.eventTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),

      // TODO add deployment info (LIVE & PRELIVE)

      // TODO test DELETED events

      // TODO add Anonymous Search Manager

      ActivityLog(
        items = activityLogItems
      )
    }
  }

}
