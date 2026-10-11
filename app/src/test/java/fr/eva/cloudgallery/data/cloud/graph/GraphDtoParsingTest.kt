package fr.eva.cloudgallery.data.cloud.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Echantillons inspirés de la documentation Microsoft Graph (driveItem: delta, driveItem, drive). */
class GraphDtoParsingTest {

    private fun page(json: String): DeltaPage = GraphJson.decodeFromString(DeltaPage.serializer(), json)

    @Test
    fun nextLinkPage_withItemsAndUnknownFields() {
        val p = page(
            """
            {
              "@odata.context": "https://graph.microsoft.com/v1.0/${'$'}metadata#Collection(driveItem)",
              "@odata.nextLink": "https://graph.microsoft.com/v1.0/me/drive/delta(token=1230919asd190410jlka)",
              "value": [
                {
                  "id": "0123456789abc",
                  "name": "folder2",
                  "folder": { },
                  "parentReference": { "id": "ROOTID", "driveId": "d1", "driveType": "personal" },
                  "someFutureField": { "nested": [1, 2, 3] }
                },
                {
                  "id": "123010204abac",
                  "name": "IMG_0001.JPG",
                  "size": 2048,
                  "cTag": "aYzozMjE7MzA",
                  "eTag": "aMzozMjE7MzA",
                  "createdDateTime": "2024-06-01T10:00:00Z",
                  "lastModifiedDateTime": "2024-06-02T10:00:00.123Z",
                  "fileSystemInfo": { "createdDateTime": "2024-05-30T08:00:00Z", "lastModifiedDateTime": "2024-05-31T08:00:00Z" },
                  "file": { "mimeType": "image/jpeg", "hashes": { "quickXorHash": "Q1hB", "sha1Hash": "ABCDEF" } },
                  "image": { "width": 4000, "height": 3000 },
                  "photo": { "takenDateTime": "2024-05-29T07:00:00Z", "cameraMake": "samsung" },
                  "parentReference": { "id": "0123456789abc" }
                }
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(p.nextLink)
        assertNull(p.deltaLink)
        assertEquals(2, p.value.size)
        val folder = p.value[0]
        assertNotNull(folder.folder)
        assertNull(folder.folder?.childCount)
        val file = p.value[1]
        assertEquals("image/jpeg", file.file?.mimeType)
        assertEquals("Q1hB", file.file?.hashes?.quickXorHash)
        assertEquals("ABCDEF", file.file?.hashes?.sha1Hash)
        assertEquals(4000, file.image?.width)
        assertEquals("2024-05-29T07:00:00Z", file.photo?.takenDateTime)
        assertEquals("2024-05-30T08:00:00Z", file.fileSystemInfo?.createdDateTime)
        assertEquals(2048L, file.size)
        assertEquals("aYzozMjE7MzA", file.cTag)
    }

    @Test
    fun deltaLinkPage_withDeletedItems() {
        val p = page(
            """
            {
              "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=1230919asd190410jlka",
              "value": [
                { "id": "2353010204ddgg", "name": "file5.txt", "deleted": { } },
                { "id": "9988", "deleted": { "state": "deleted" }, "folder": { } }
              ]
            }
            """.trimIndent(),
        )
        assertNull(p.nextLink)
        assertTrue(p.deltaLink!!.contains("token="))
        assertNotNull(p.value[0].deleted)
        assertNull(p.value[0].file)
        assertEquals("deleted", p.value[1].deleted?.state)
        assertNotNull(p.value[1].folder)
    }

    @Test
    fun rootPackageAndRemoteItem_facetsAreDetected() {
        val p = page(
            """
            {
              "value": [
                { "id": "ROOTID", "name": "root", "root": { }, "folder": { "childCount": 4 } },
                { "id": "NB1", "name": "Notes", "package": { "type": "oneNote" } },
                { "id": "R1", "name": "Shared", "remoteItem": { "id": "X", "parentReference": { "driveId": "other" } } }
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(p.value[0].root)
        assertEquals(4, p.value[0].folder?.childCount)
        assertNotNull(p.value[1].pkg)
        assertNotNull(p.value[2].remoteItem)
        assertNull(p.nextLink)
        assertNull(p.deltaLink)
    }

    @Test
    fun video_durationInMilliseconds_andNullsCoerced() {
        val p = page(
            """
            {
              "value": [
                {
                  "id": "V1",
                  "name": "clip.mp4",
                  "size": null,
                  "file": { "mimeType": "video/mp4", "hashes": null },
                  "video": { "duration": 12345, "width": 1920, "height": 1080, "bitrate": 1000 }
                }
              ]
            }
            """.trimIndent(),
        )
        val v = p.value.single()
        assertEquals(12_345L, v.video?.duration)
        assertEquals(1920, v.video?.width)
        assertNull(v.size)
        assertNull(v.file?.hashes)
    }

    @Test
    fun emptyPage_defaultsToNoItems() {
        val p = page("""{ "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=x" }""")
        assertTrue(p.value.isEmpty())
    }

    @Test
    fun driveMeAndError_parse() {
        val drive = GraphJson.decodeFromString(
            DriveDto.serializer(),
            """{ "id": "b!abc", "driveType": "personal", "owner": { "user": { "displayName": "X" } },
               "quota": { "deleted": 0, "remaining": 750, "state": "normal", "total": 1000, "used": 250 } }""",
        )
        assertEquals("b!abc", drive.id)
        assertEquals(1000L, drive.quota?.total)
        assertEquals(250L, drive.quota?.used)

        val me = GraphJson.decodeFromString(MeDto.serializer(), """{ "displayName": "Eva", "mail": null, "userPrincipalName": "eva@outlook.fr" }""")
        assertNull(me.mail)
        assertEquals("eva@outlook.fr", me.userPrincipalName)

        val error = GraphJson.decodeFromString(
            GraphErrorEnvelope.serializer(),
            """{ "error": { "code": "resyncChangesApplyDifferences", "message": "Resync required", "innerError": { } } }""",
        )
        assertEquals("resyncChangesApplyDifferences", error.error?.code)
    }
}
