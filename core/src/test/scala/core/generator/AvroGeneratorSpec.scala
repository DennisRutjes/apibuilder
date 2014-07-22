package core.generator

import core.{ TestHelper, ServiceDescription }
import org.scalatest.{ ShouldMatchers, FunSpec }

class AvroGeneratorSpec extends FunSpec with ShouldMatchers {

  describe("for example json") {

    it("should generate correct Avro schema") {
      val schema = AvroSchemas(TestHelper.readFile("core/src/test/resources/avro/example.json"))
      //println(schema)
      cleanws(schema) should be(cleanws(TestHelper.readFile("core/src/test/resources/avro/example.avsc")))
    }

  }


  def cleanws(s: String): String = s.replaceAll("\\s","")
}
