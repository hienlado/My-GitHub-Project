package com.hien.rtkmultidevice.export

import com.hien.rtkmultidevice.domain.model.Project
import com.hien.rtkmultidevice.domain.model.SurveyPoint
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvExporterTest {
    @Test
    fun buildProjectCsv_quotesCommaAndQuoteValues() {
        val project = Project(id = 1, name = "Khu A, B")
        val point = SurveyPoint(
            projectId = 1,
            pointCode = "P001",
            latitude = 10.123456789,
            longitude = 106.987654321,
            altitude = 5.25,
            geoidSeparation = 1.1,
            northing = 1122334.567,
            easting = 612345.678,
            centralMeridian = 105.0,
            fixQuality = 4,
            hdop = 0.8,
            pdop = 1.2,
            satelliteCount = 18,
            timestamp = 1_700_000_000_000,
            note = "Moc \"ranh\", canh"
        )

        val csv = CsvExporter.buildProjectCsv(project, listOf(point))

        assertTrue(csv.startsWith("project_name,point_code,latitude"))
        assertTrue(csv.contains("\"Khu A, B\",P001,10.12345679,106.98765432"))
        assertTrue(csv.contains("\"Moc \"\"ranh\"\", canh\""))
    }
}
