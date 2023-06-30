package io.iohk.scevm.rpc.serialization

import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, BlockHash}
import io.iohk.scevm.rpc.JsonHelper
import io.iohk.scevm.rpc.controllers.BlockParam
import io.iohk.scevm.rpc.controllers.EthTransactionController._
import io.iohk.scevm.testing.Generators
import org.json4s.{Formats, MappingException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.{Failure, Try}

class JsonSerializersSpec extends AnyWordSpec with Matchers with JsonHelper with ScalaCheckPropertyChecks {

  private val formats: Formats = JsonSerializers.formats

  "JsonSerializers.GetLogsRequestSerializer" when {
    "deserializePartial is called" should {
      "parse request correctly when all parameters are filled (range version)" in {
        val circeJson =
          json"""
            {
              "fromBlock": "earliest",
              "toBlock": "latest",
              "address": "0x11111aaaaa22222bbbbb33333ccccc44444ddddd",
              "topics": [
                ["0x56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8"],
                null,
                "0x56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8"
              ]
            }
            """

        val expectedExactTopicSearch = ExactTopicSearch(
          Hex.decodeUnsafe("56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8")
        )
        val expectedRequest = GetLogsRequestByRange(
          fromBlock = BlockParam.Earliest,
          toBlock = BlockParam.Latest,
          address = Seq(Address(Hex.decodeUnsafe("11111aaaaa22222bbbbb33333ccccc44444ddddd"))),
          topics = Seq(
            OrTopicSearch(Set(expectedExactTopicSearch)),
            AnythingTopicSearch,
            expectedExactTopicSearch
          )
        )

        val result = JsonSerializers.GetLogsRequestSerializer
          .deserializePartial(formats)
          .lift(circeToJson4s(circeJson))

        result shouldBe Some(expectedRequest)
      }

      "parse request correctly when all parameters are filled (block hash version)" in {
        val circeJson =
          json"""
            {
              "blockhash": "0x1111111111222222222233333333334444444444555555555566666666667777",
              "address": "0x11111aaaaa22222bbbbb33333ccccc44444ddddd",
              "topics": [
                ["0x56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8"],
                null,
                "0x56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8"
              ]
            }
            """

        val expectedExactTopicSearch = ExactTopicSearch(
          Hex.decodeUnsafe("56ae0cf797dc5c06b48e3f1f0ac14db02bbbeff12a70e489e5a2403ffc021bb8")
        )
        val expectedRequest = GetLogsRequestByBlockHash(
          blockHash = BlockHash(Hex.decodeUnsafe("1111111111222222222233333333334444444444555555555566666666667777")),
          address = Seq(Address(Hex.decodeUnsafe("11111aaaaa22222bbbbb33333ccccc44444ddddd"))),
          topics = Seq(
            OrTopicSearch(Set(expectedExactTopicSearch)),
            AnythingTopicSearch,
            expectedExactTopicSearch
          )
        )

        val result = JsonSerializers.GetLogsRequestSerializer
          .deserializePartial(formats)
          .lift(circeToJson4s(circeJson))

        result shouldBe Some(expectedRequest)
      }

      "return None if both blockhash and fromBlock/toBlock are defined" in {
        val circeJson =
          json"""
            {
              "fromBlock": "earliest",
              "toBlock": "latest",
              "blockhash": "0x1111111111222222222233333333334444444444555555555566666666667777"
            }
            """

        val result = Try(
          JsonSerializers.GetLogsRequestSerializer
            .deserializePartial(formats)
            .lift(circeToJson4s(circeJson))
        )

        result match {
          case Failure(exception) if exception.isInstanceOf[MappingException] => succeed
          case _                                                              => fail()
        }
      }

      "parse request correctly when parameter are missing" in {
        val circeJson = json"{}"

        val expectedRequest = GetLogsRequestByRange(
          fromBlock = BlockParam.Latest,
          toBlock = BlockParam.Latest,
          address = Seq.empty[Address],
          topics = Seq.empty[TopicSearch]
        )

        val result = JsonSerializers.GetLogsRequestSerializer
          .deserializePartial(formats)
          .lift(circeToJson4s(circeJson))

        result shouldBe Some(expectedRequest)
      }

      "parse request correctly when address is an array" in {
        val circeJson =
          json"""
            {
              "address": [
                "0x1111111111222222222233333333334444444444",
                "0x5555555555666666666677777777778888888888"
              ]
            }
            """

        val expectedRequest = GetLogsRequestByRange(
          fromBlock = BlockParam.Latest,
          toBlock = BlockParam.Latest,
          address = Seq(
            Address(Hex.decodeUnsafe("1111111111222222222233333333334444444444")),
            Address(Hex.decodeUnsafe("5555555555666666666677777777778888888888"))
          ),
          topics = Seq.empty[TopicSearch]
        )

        val result = JsonSerializers.GetLogsRequestSerializer
          .deserializePartial(formats)
          .lift(circeToJson4s(circeJson))

        result shouldBe Some(expectedRequest)
      }
    }
  }

  "ByteStringKeySerializer" should {
    "serialize to a valid hexadecimal format" in {
      forAll(Generators.byteStringOfLengthNGen(32)) { input =>
        val result = JsonSerializers.ByteStringKeySerializer
          .serialize(formats)
          .lift(input)
          .get

        assert(result.startsWith("0x"))
        assert(result.length % 2 == 0)
      }
    }

    "serialize Map[ByteString, _]" in {
      import org.json4s._
      import org.json4s.native.JsonMethods._

      val input = {
        val byteStringKey1 = ByteString(JsonMethodsImplicits.decode("123abba"))
        val byteStringKey2 = ByteString(JsonMethodsImplicits.decode("0xcafe"))
        Map(
          byteStringKey1 -> "value1",
          byteStringKey2 -> "value2"
        )
      }

      val resultRawJson = {
        val json = Extraction.decompose(input)(formats)
        compact(render(json))
      }

      val expectedCirceJson =
        json"""
       {
          "0x0123abba" : "value1",
          "0xcafe" : "value2"
        }
       """

      io.circe.parser.parse(resultRawJson) shouldBe Right(expectedCirceJson)
    }

    "throw at deserialization (because it is not defined)" in {
      import org.json4s._
      import org.json4s.native.JsonMethods

      val input =
        """
       {
          "123":"value1",
          "cafe":"value2"
        }
       """

      implicit val _formats = formats
      assertThrows[MappingException](JsonMethods.parse(input).extract[Map[ByteString, String]])
    }
  }
}
