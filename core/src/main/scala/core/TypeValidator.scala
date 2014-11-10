package core

import java.util.UUID
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import com.fasterxml.jackson.core.{ JsonParseException, JsonProcessingException }
import com.fasterxml.jackson.databind.JsonMappingException

case class TypeValidatorEnums(name: String, values: Seq[String])

object TypeValidator {
  private[core] val BooleanValues = Seq("true", "false")
}

case class TypeValidator(
  enums: Seq[TypeValidatorEnums] = Seq.empty
) {

  private[this] val dateTimeISOParser = ISODateTimeFormat.dateTimeParser()

  private def parseJsonOrNone(value: String): Option[JsValue] = {
    try {
      Some(
        Json.parse(value)
      )
    } catch {
      case e: JsonParseException => None
      case e: JsonProcessingException => None
      case e: JsonMappingException => None
      case e: Throwable => throw e
    }
  }

  def assertValidDefault(t: Type, value: String) {
    validate(t, value) match {
      case None => ()
      case Some(msg) => sys.error(msg)
    }
  }

  def validateTypeInstance(
    typeInstance: TypeInstance,
    value: String,
    errorPrefix: Option[String] = None
  ): Option[String] = {
    typeInstance.container match {
      case TypeContainer.Singleton => {
        validate(typeInstance.`type`, value, errorPrefix)
      }

      case TypeContainer.List => {
        parseJsonOrNone(value) match {
          case None => {
            Some(s"default[$value] is not valid json")
          }
          case Some(json) => {
            json.asOpt[JsArray] match {
              case Some(v) => {
                Some(
                  v.value.flatMap { value =>
                    validate(typeInstance.`type`, JsonUtil.asOptString(value).getOrElse(""), errorPrefix)
                  }.mkString(", ")
                )
              }
              case None => {
                Some(s"default[$value] is not a valid list[${typeInstance.typeName}]")
              }
            }
          }
        }
      }

      case TypeContainer.Map => {
        parseJsonOrNone(value) match {
          case None => {
            Some(s"default[$value] is not valid json")
          }
          case Some(json) => {
            json.asOpt[JsObject] match {
              case Some(v) => {
                Some(
                  v.value.flatMap {
                    case (key, value) => {
                      validate(typeInstance.`type`, JsonUtil.asOptString(value).getOrElse(""), errorPrefix)
                    }
                  }.mkString(", ")
                )
              }
              case None => {
                Some(s"default[$value] is not a valid map[${typeInstance.typeName}]")
              }
            }
          }
        }
      }
    }
  }

  def validate(
    t: Type,
    value: String,
    errorPrefix: Option[String] = None
  ): Option[String] = {
    t match {

      case Type.Enum(name) => {
        enums.find(_.name == name) match {
          case None => Some(s"could not find enum named[$name]")
          case Some(enum) => {
            enum.values.find(_ == value) match {
              case None => {
                Some(
                  withPrefix(
                    errorPrefix,
                    s"default[$value] is not a valid value for enum[$name]. Valid values are: " + enum.values.mkString(", ")
                  )
                )
              }
              case Some(_) => None
            }
          }
        }
      }
      
      case Type.Model(name) => {
        Some(withPrefix(errorPrefix, s"default[$value] is not valid for model[$name]. apidoc does not support default values for models"))
      }

      case Type.Primitive(Primitives.Boolean) => {
        if (TypeValidator.BooleanValues.contains(value)) {
          None
        } else {
          Some(withPrefix(errorPrefix, s"Value[$value] is not a valid boolean. Must be one of: ${TypeValidator.BooleanValues.mkString(", ")}"))
        }
      }

      case Type.Primitive(Primitives.Double) => {
        try {
          value.toDouble
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid double"))
        }
      }

      case Type.Primitive(Primitives.Integer) => {
        try {
          value.toInt
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid integer"))
        }
      }

      case Type.Primitive(Primitives.Long) => {
        try {
          value.toLong
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid long"))
        }
      }

      case Type.Primitive(Primitives.Decimal) => {
        try {
          BigDecimal(value)
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid decimal"))
        }
      }

      case Type.Primitive(Primitives.Unit) => {
        if (value == "") {
          None
        } else {
          Some(withPrefix(errorPrefix, s"Value[$value] is not a valid unit type - must be the empty string"))
        }
      }

      case Type.Primitive(Primitives.Uuid) => {
        try {
          UUID.fromString(value)
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid uuid"))
        }
      }

      case Type.Primitive(Primitives.DateIso8601) => {
        try {
          dateTimeISOParser.parseDateTime(s"${value}T00:00:00Z")
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid date-iso8601"))
        }
      }

      case Type.Primitive(Primitives.DateTimeIso8601) => {
        try {
          dateTimeISOParser.parseDateTime(value)
          None
        } catch {
          case _: Throwable => Some(withPrefix(errorPrefix, s"Value[$value] is not a valid date-time-iso8601"))
        }
      }

      case Type.Primitive(Primitives.String) => {
        None
      }
    }
  }

  private def withPrefix(
    prefix: Option[String],
    msg: String
  ) = prefix match {
    case None => msg
    case Some(p) => s"$p $msg"
  }

}
