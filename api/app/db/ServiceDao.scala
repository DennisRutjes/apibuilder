package db

import com.gilt.apidoc.models.{Error, Organization, Service, User, Visibility}
import lib.{UrlKey, Validation}
import anorm._
import play.api.db._
import play.api.libs.json._
import play.api.Play.current
import java.util.UUID

case class ServiceForm(
  name: String,
  description: Option[String] = None,
  visibility: Visibility
)

object ServiceForm {
  import com.gilt.apidoc.models.json._
  implicit val jsonReadsServiceForm = Json.reads[ServiceForm]
}

object ServiceDao {

  private val BaseQuery = """
    select services.guid, services.name, services.key, services.description, services.visibility
      from services
      join organizations on organizations.guid = services.organization_guid and organizations.deleted_at is null
     where services.deleted_at is null
  """

  private val InsertQuery = """
    insert into services
    (guid, organization_guid, name, description, key, visibility, created_by_guid, updated_by_guid)
    values
    ({guid}::uuid, {organization_guid}::uuid, {name}, {description}, {key}, {visibility}, {created_by_guid}::uuid, {created_by_guid}::uuid)
  """

  private val UpdateQuery = """
    update services
       set name = {name},
           visibility = {visibility},
           description = {description},
           updated_by_guid = {updated_by_guid}::uuid
     where guid = {guid}::uuid
  """

  def validate(
    org: Organization,
    form: ServiceForm,
    existing: Option[Service]
  ): Seq[Error] = {
    val nameErrors = findByOrganizationAndName(org, form.name) match {
      case None => Seq.empty
      case Some(service: Service) => {
        if (existing.map(_.guid) == Some(service.guid)) {
          Seq.empty
        } else {
          Seq("Service with this name already exists")
        }
      }
    }

    Validation.errors(nameErrors)
  }

  def update(updatedBy: User, dao: Service) {
    DB.withConnection { implicit c =>
      SQL(UpdateQuery).on(
        'guid -> dao.guid,
        'name -> dao.name,
        'visibility -> dao.visibility.toString,
        'description -> dao.description,
        'updated_by_guid -> updatedBy.guid
      ).execute()
    }
  }

  def create(
    createdBy: User,
    org: Organization,
    form: ServiceForm,
    keyOption: Option[String] = None
  ): Service = {
    val guid = UUID.randomUUID
    val key = keyOption.getOrElse(UrlKey.generate(form.name))
    DB.withConnection { implicit c =>
      SQL(InsertQuery).on(
        'guid -> guid,
        'organization_guid -> org.guid,
        'name -> form.name,
        'description -> form.description,
        'key -> key,
        'visibility -> form.visibility.toString,
        'created_by_guid -> createdBy.guid,
        'updated_by_guid -> createdBy.guid
      ).execute()
    }

    global.Actors.mainActor ! actors.MainActor.Messages.ServiceCreated(guid)    

    findAll(Authorization.All, orgKey = Some(org.key), key = Some(key)).headOption.getOrElse {
      sys.error("Failed to create service")
    }
  }

  def softDelete(deletedBy: User, service: Service) {
    SoftDelete.delete("services", deletedBy, service.guid)
  }

  private[db] def findByOrganizationAndName(org: Organization, name: String): Option[Service] = {
    findAll(Authorization.All, orgKey = Some(org.key), name = Some(name), limit = 1).headOption
  }

  def findByOrganizationKeyAndServiceKey(authorization: Authorization, orgKey: String, serviceKey: String): Option[Service] = {
    findAll(authorization, orgKey = Some(orgKey), key = Some(serviceKey), limit = 1).headOption
  }

  def findByGuid(authorization: Authorization, guid: UUID): Option[Service] = {
    findAll(authorization, guid = Some(guid), limit = 1).headOption
  }

  def findAll(
    authorization: Authorization,
    orgKey: Option[String] = None,
    guid: Option[UUID] = None,
    name: Option[String] = None,
    key: Option[String] = None,
    limit: Int = 50,
    offset: Int = 0
  ): Seq[Service] = {
    val sql = Seq(
      Some(BaseQuery.trim),
      authorization match {
        case Authorization.All => None
        case Authorization.PublicOnly => Some(s"and services.visibility = '${Visibility.Public.toString}'")
        case Authorization.User(userGuid) => {
          Some(
            s"and (services.visibility = '${Visibility.Public.toString}' or " +
              "organizations.guid in (" +
              "select organization_guid from memberships where deleted_at is null and user_guid = {authorization_user_guid}::uuid" +
            "))"
          )
        }
      },
      guid.map { v => "and services.guid = {guid}::uuid" },
      orgKey.map { v => "and services.organization_guid = (select guid from organizations where deleted_at is null and key = {organization_key})" },
      name.map { v => "and lower(trim(services.name)) = lower(trim({name}))" },
      key.map { v => "and services.key = lower(trim({key}))" },
      Some(s"order by lower(services.name) limit ${limit} offset ${offset}")
    ).flatten.mkString("\n   ")

    val authorizationUserGuid = authorization match {
      case Authorization.User(guid) => Some(guid)
      case _ => None
    }

    val bind = Seq[Option[NamedParameter]](
      guid.map('guid -> _.toString),
      authorizationUserGuid.map('authorization_user_guid -> _.toString),
      orgKey.map('organization_key -> _),
      name.map('name -> _),
      key.map('key ->_)
    ).flatten

    DB.withConnection { implicit c =>
      SQL(sql).on(bind: _*)().toList.map { fromRow(_) }.toSeq
    }
  }

  private[db] def fromRow(
    row: anorm.Row,
    prefix: Option[String] = None
  ): Service = {
    val p = prefix.map( _ + "_").getOrElse("")
    Service(
      guid = row[UUID](s"${p}guid"),
      name = row[String](s"${p}name"),
      key = row[String](s"${p}key"),
      visibility = Visibility(row[String](s"${p}visibility")),
      description = row[Option[String]](s"${p}description")
    )
  }

}
