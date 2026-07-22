package com.hien.rtkmultidevice.export

import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import java.util.Locale

object CsvExporter {
    fun buildProjectCsv(project: Project, points: List<SurveyPoint>): String {
        val rows = buildList {
            add(
                listOf(
                    "project_name",
                    "point_code",
                    "latitude",
                    "longitude",
                    "altitude_m",
                    "geoid_separation_m",
                    "northing_m",
                    "easting_m",
                    "central_meridian",
                    "zone_width_deg",
                    "fix_quality",
                    "fix_label",
                    "hdop",
                    "pdop",
                    "satellite_count",
                    "timestamp",
                    "note"
                ).toCsvLine()
            )
            points.forEach { point ->
                add(
                    listOf(
                        project.name,
                        point.pointCode,
                        point.latitude.format(8),
                        point.longitude.format(8),
                        point.altitude.format(4),
                        point.geoidSeparation.format(4),
                        point.northing.format(3),
                        point.easting.format(3),
                        point.centralMeridian.format(3),
                        point.zoneWidthDeg.toString(),
                        point.fixQuality.toString(),
                        point.fixLabel,
                        point.hdop.format(2),
                        point.pdop.format(2),
                        point.satelliteCount.toString(),
                        point.timestampFormatted,
                        point.note
                    ).toCsvLine()
                )
            }
        }
        return rows.joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * CSV GỘP: tất cả điểm ĐO từ nhiều job (mỗi dòng gồm cột project_name).
     * Danh sách đã được lọc sẵn (chỉ điểm đo thực địa) trước khi gọi.
     */
    fun buildAllMeasuredCsv(entries: List<Pair<Project, SurveyPoint>>): String {
        val rows = buildList {
            add(
                listOf(
                    "project_name", "point_code", "latitude", "longitude", "altitude_m",
                    "geoid_separation_m", "northing_m", "easting_m", "central_meridian",
                    "zone_width_deg", "fix_quality", "fix_label", "hdop", "pdop",
                    "satellite_count", "timestamp", "note"
                ).toCsvLine()
            )
            entries.forEach { (project, point) ->
                add(
                    listOf(
                        project.name, point.pointCode, point.latitude.format(8), point.longitude.format(8),
                        point.altitude.format(4), point.geoidSeparation.format(4), point.northing.format(3),
                        point.easting.format(3), point.centralMeridian.format(3), point.zoneWidthDeg.toString(),
                        point.fixQuality.toString(), point.fixLabel, point.hdop.format(2), point.pdop.format(2),
                        point.satelliteCount.toString(), point.timestampFormatted, point.note
                    ).toCsvLine()
                )
            }
        }
        return rows.joinToString(separator = "\n", postfix = "\n")
    }

    private fun Double.format(decimals: Int): String =
        "%.${decimals}f".format(Locale.US, this)

    private fun List<String>.toCsvLine(): String =
        joinToString(",") { value ->
            val escaped = value.replace("\"", "\"\"")
            if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"$escaped\""
            } else {
                escaped
            }
        }
}
