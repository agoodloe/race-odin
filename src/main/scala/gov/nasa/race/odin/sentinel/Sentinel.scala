/*
 * Copyright (c) 2022, United States Government, as represented by the
 * Administrator of the National Aeronautics and Space Administration.
 * All rights reserved.
 *
 * The RACE - Runtime for Airspace Concept Evaluation platform is licensed
 * under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package gov.nasa.race.odin.sentinel

import gov.nasa.race.common.ConstAsciiSlice.asc
import gov.nasa.race.common.{JsonSerializable, JsonWriter, UTF8JsonPullParser}
import gov.nasa.race.uom.DateTime
import gov.nasa.race.{Dated, ifSome}

import scala.collection.mutable.ArrayBuffer
import SentinelSensorReading._

/**
 * Sentinel data model for Delphire's powerline fire sensors (see https://delphiretech.com/).
 */
object Sentinel {

  //--- lexical constants
  val ID = asc("id")
  val DATA = asc("data")
  val IMAGES = asc("images")
  val INFO = asc("info")
  val NO = asc("no")
  val PART = asc("partNo")
  val CAPS = asc("capabilities")
  val CONFS = asc("camConfs")
  val CLAIMS = asc("claims")
  val EVIDENCE = asc("evidences")

  def writeReadingMemberTo (w: JsonWriter, name: CharSequence, date: DateTime)(f: JsonWriter=>Unit): Unit = {
    w.writeObjectMember(name) { w =>
      w.writeDateTimeMember(TIME_RECORDED,date)
      f(w)
    }
  }
}
import Sentinel._

/**
  * Sentinel device state
 * This is basically a time-stamped snapshot of accumulated SentinelReading records
  */
case class Sentinel (
                      id: String, // deviceId
                      date: DateTime = DateTime.UndefinedDateTime, // last sensor update
                      gps: Option[SentinelGpsReading] = None,
                      gyro: Option[SentinelGyroReading] = None,
                      mag: Option[SentinelMagReading] = None,
                      accel: Option[SentinelAccelReading] = None,
                      gas: Option[SentinelGasReading] = None,
                      thermo: Option[SentinelThermoReading] = None,
                      voc: Option[SentinelVocReading] = None,
                      anemo: Option[SentinelAnemoReading] = None,
                      fire: Option[SentinelFireReading] = None,
                      images: Seq[SentinelCameraReading] = Seq.empty
               ) extends Dated with JsonSerializable {

  def serializeMembersTo (w: JsonWriter): Unit = {
    w.writeStringMember(DEVICE_ID, id)
    w.writeDateTimeMember(TIME_RECORDED,date)

    ifSome(gps){ _.serializeAsMemberTo(w) }
    ifSome(gyro){ _.serializeAsMemberTo(w) }
    ifSome(mag){ _.serializeAsMemberTo(w) }
    ifSome(accel){ _.serializeAsMemberTo(w) }
    ifSome(gas){ _.serializeAsMemberTo(w) }
    ifSome(thermo){ _.serializeAsMemberTo(w) }
    ifSome(voc){ _.serializeAsMemberTo(w) }
    ifSome(anemo){ _.serializeAsMemberTo(w) }
    ifSome(fire){ _.serializeAsMemberTo(w) }

    if (images.nonEmpty) {
      w.writeArrayMember(IMAGES) { w=>
        images.foreach( img=> w.writeObject( img.serializeMembersTo))
      }
    }
  }

  def updateWith (update: SentinelSensorReading): Sentinel = {
    if (update.deviceId != id) return this  // not for us

    update match {
      case r: SentinelGpsReading => copy(date=r.date, gps=Some(r))
      case r: SentinelGyroReading => copy(date=r.date, gyro=Some(r))
      case r: SentinelMagReading => copy(date=r.date, mag=Some(r))
      case r: SentinelAccelReading => copy(date=r.date, accel=Some(r))
      case r: SentinelGasReading => copy(date=r.date, gas=Some(r))
      case r: SentinelThermoReading => copy(date=r.date, thermo=Some(r))
      case r: SentinelVocReading => copy(date=r.date, voc=Some(r))
      case r: SentinelAnemoReading => copy(date=r.date, anemo=Some(r))
      case r: SentinelFireReading => copy(date=r.date, fire=Some(r))
      case r: SentinelCameraReading => copy(date=r.date, images=addImage(r))
    }
  }

  def addImage (r: SentinelCameraReading): Seq[SentinelCameraReading] = {
    // TODO - we probably want to cap the list size
    r +: images
  }
}

/**
 * this parses Sentinel records received from the Delphire server, which are of the form
 * {
 *   "data": [
 *       {
 *           "id": "dizwqq96w36j",  // was numeric
 *           "timeRecorded": ""2022-09-07T02:41:57.000Z"" // was epoch seconds,
 *           "sensorNo": 0,
 *           "deviceId": 18,
 *           "<sensor-type>": { sensor-data }
 *       }, ...
 *   ]
 * }
 */
class SentinelParser extends UTF8JsonPullParser
    with SentinelAccelParser with SentinelAnemoParser with SentinelGasParser with SentinelMagParser with SentinelThermoParser
    with SentinelFireParser with SentinelGyroParser with SentinelCameraParser with SentinelVocParser with SentinelGpsParser {

  def parse(): Seq[SentinelSensorReading] = {
    val updates = ArrayBuffer.empty[SentinelSensorReading]
    var recordId: String = null
    var deviceId: String = null
    var sensorId = -1
    var timeRecorded = DateTime.UndefinedDateTime

    def appendSomeRecording (maybeReading: Option[SentinelSensorReading]): Unit = {
      ifSome(maybeReading){ r=>
        if (deviceId != null && timeRecorded.isDefined) {
          updates += r
        }
      }
    }

    ensureNextIsObjectStart()
    foreachMemberInCurrentObject {
      case DATA =>
        foreachElementInCurrentArray {
          recordId = null
          deviceId = null
          sensorId = -1
          timeRecorded = DateTime.UndefinedDateTime

          foreachMemberInCurrentObject {
                  // NOTE - this relies on member order in the serialization
            case ID => recordId = value.toString() // we accept both quoted and unquoted (int -> string)
            case DEVICE_ID => deviceId = value.intern // ditto
            case SENSOR_NO => sensorId = unQuotedValue.toInt
            case TIME_RECORDED => timeRecorded = dateTimeValue

            case GPS => appendSomeRecording( parseGpsValue( deviceId, sensorId, timeRecorded))
            case GAS => appendSomeRecording( parseGasValue( deviceId, sensorId, timeRecorded))
            case ACCEL => appendSomeRecording( parseAccelValue( deviceId, sensorId, timeRecorded))
            case ANEMO => appendSomeRecording( parseWindValue( deviceId, sensorId, timeRecorded))
            case GYRO => appendSomeRecording( parseGyroValue( deviceId, sensorId, timeRecorded))
            case THERMO => appendSomeRecording( parseThermoValue( deviceId, sensorId, timeRecorded))
            case MAG => appendSomeRecording( parseMagValue( deviceId, sensorId, timeRecorded))
            case FIRE => appendSomeRecording( parseFireValue( deviceId, sensorId, timeRecorded))
            case VOC => appendSomeRecording( parseVocValue( deviceId, sensorId, timeRecorded))
            case CAMERA => appendSomeRecording( parseCameraValue( deviceId, sensorId, timeRecorded))

            case CLAIMS => skipPastAggregate()
            case EVIDENCE => skipPastAggregate()
            case _ => // ignore other members
          }
        }

      case _ => // ignore other members
    }

    updates.toSeq
  }

  case class SentinelDeviceInfo (deviceId: String, info: String)

  /**
   * until device/recordIds are properly separated we have to parse this separately. The silver lining is
   * that we only expect a subset of above message format in this case
   *
   *   "data": [
   *       {
   *          "id": "dizwqq96w36j",
   *          "info": "test"
   *       }
   *    ], ...
   */
  def parseDevices(): Seq[SentinelDeviceInfo] = {
    val deviceList = ArrayBuffer.empty[SentinelDeviceInfo]

    ensureNextIsObjectStart()
    foreachMemberInCurrentObject {
      case DATA =>
        foreachElementInCurrentArray {
          var deviceId: String = null
          var info: String = ""

          foreachMemberInCurrentObject {
            case ID => deviceId = quotedValue.intern
            case INFO => info = quotedValue.toString()
          }
          if (deviceId != null) deviceList += SentinelDeviceInfo(deviceId,info)
        }
      case _ => // ignore other members
    }
    deviceList.toSeq
  }

  case class SentinelSensorInfo (sensorNo: Int, partNo: String, capabilities: Seq[String])

  /**
   * another initialization message to get sensors for a given device - see parseDevices()
   *   {
   *      "no": 0,
   *      "partNo": "ICM20x",
   *      "camConfs": [],
   *      "capabilities": [
   *         "accelerometer",
   *         "gyroscope",
   *         "magnetometer"
   *       ]
   *   }, ...
   */
  def parseSensors(): Seq[SentinelSensorInfo] = {
    val sensorList = ArrayBuffer.empty[SentinelSensorInfo]

    ensureNextIsObjectStart()
    foreachMemberInCurrentObject {
      case DATA =>
        foreachElementInCurrentArray {
          var sensorNo: Int = -1
          var part: String = ""
          var caps = Seq.empty[String]

          foreachMemberInCurrentObject {
            case NO => sensorNo = unQuotedValue.toInt
            case PART => part = quotedValue.toString()
            case CONFS => skipPastAggregate() // ignore for now
            case CAPS => caps = readCurrentStringArray()
          }
          if (sensorNo >= 0) sensorList += SentinelSensorInfo(sensorNo,part,caps)
        }
      case _ => // ignore other members
    }

    sensorList.toSeq
  }
}


