package nl.knaw.huc.broccoli.resources

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.swagger.v3.oas.annotations.Operation
import nl.knaw.huc.broccoli.api.AnnoTextBody
import nl.knaw.huc.broccoli.api.AnnoTextResult
import nl.knaw.huc.broccoli.api.IIIFContext
import nl.knaw.huc.broccoli.api.Request
import nl.knaw.huc.broccoli.api.ResourcePaths.REPUBLIC
import nl.knaw.huc.broccoli.config.BroccoliConfiguration
import nl.knaw.huc.broccoli.service.IIIFStore
import nl.knaw.huc.broccoli.service.ResourceLoader
import org.eclipse.jetty.util.ajax.JSON
import org.glassfish.jersey.client.ClientProperties
import org.slf4j.LoggerFactory
import javax.ws.rs.*
import javax.ws.rs.client.Client
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

@Path(REPUBLIC)
@Produces(MediaType.APPLICATION_JSON)
class RepublicResource(
    private val configuration: BroccoliConfiguration,
    private val iiifStore: IIIFStore,
    private val client: Client
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GET
    @Path("v0")
    @Operation(description = "Get text, annotations and iiif details for a given volume and opening")
    fun getVolumeOpening(
        @QueryParam("volume") _volume: String?,
        @QueryParam("opening") _opening: Int?,
        @QueryParam("bodyId") _bodyId: String?
    ): Response {
        val volume = _volume ?: configuration.republic.defaultVolume
        val opening = _opening ?: configuration.republic.defaultOpening

        log.info("volume: $volume, opening: $opening, bodyId: $_bodyId")

        if (_bodyId == null) {
            return Response.ok(buildResult(volume, opening)).build()
        }

        val bodyId = _bodyId.removePrefix("urn:example:republic:")
        val path = "mock/$bodyId.json"
        log.info("path: $path")
        val reader = ResourceLoader.asStream(path)
        val body = jacksonObjectMapper().readValue(reader, AnnoTextBody::class.java)
        return Response.ok(body).build()
    }

    private fun buildResult(volume: String, opening: Int, bodyId: String? = null): AnnoTextResult {
        if (opening < 1) {
            throw BadRequestException("Opening count starts at 1 (but got: $opening)")
        }

        val volumeDetails = configuration.republic.volumes.find { it.name == volume }
            ?: throw NotFoundException("Volume $volume not found in republic configuration")

        val imageSet = volumeDetails.imageset
        log.info("client.timeout (before call): ${client.configuration.getProperty(ClientProperties.READ_TIMEOUT)}")
        val target = client
            .target(configuration.iiifUri)
            .path("imageset").path(imageSet).path("manifest")
        val manifest = target.uri.toString()

        val canvasId = iiifStore.getCanvasId(volume, opening)

//        val response = target.request().get()
//        log.info("iiif result: $response")
//
//        if (response.status != 200) {
//            val msg = "Fetching $manifest failed: ${response.status} ${response.statusInfo}"
//            log.info("Upstream failure: $msg")
//            throw WebApplicationException(msg)
//        }
//        data = response.readEntity(String::class.java)

        val mockedAnnotations = loadMockAnnotations()

        /* curl -LSs --data '{"body.id": "urn:republic:NL-HaNA_1.01.02_3783_0285"}' $ar/annotations
         * levert op: 1 annotatie (in .items) waar alles van opening 285 in zit. (de 'Scan')
         *
         * - in target met type=Text zit de text selector voor start en end markering van regels text
         *   EN DEZE source url moet gebruikt worden (URL escaped) bij ophalen annotaties verderop
         * - in target met type=Text maar zónder selector zit de URL naar TR met alle regels text van opening 285
         * {
          "source": "https://textrepo.republic-caf.diginfra.org/api/view/versions/42df1275-81cd-489c-b28c-345780c3889b/segments/index/49840/50066",
          "type": "Text"
          * => vervanging van getMockedText()
        },
         *
         * voor de annotaties nog een call naar annorepo:
         *   - query parameter aan broccoli call (default: overlap) of je alle annotaties voor opening wil die
         *     OP deze opening staan, of die deze opening omvatten.
         *   naar annorepo dan (containerName=volume-1728):
         *      - /search/{containerName}/overlapping_with_range
         *   OF - /search/{containerName}/within_range
         *
         * bijv. curl -LSs 'https://annorepo.republic-caf.diginfra.org/search/volume-1728/overlapping_with_range?target.source=https%3A%2F%2Ftextrepo.republic-caf.diginfra.org%2Fapi%2Frest%2Fversions%2F42df1275-81cd-489c-b28c-345780c3889b%2Fcontents&range.start=49840&range.end=50066&page=0'
         * !! Resultaat is gepagineerd.
         *
         * wegfilteren: body.type in {"TextRegion", "Line"} (column is vervallen, nu parentType van een "Line")
         * wel gewenst: body.type in {AttendanceList, Attendant, Resolution, Reviewed, Session}
         */

        return AnnoTextResult(
            request = Request(volume, opening, bodyId),
            anno = mockedAnnotations.filter { !setOf("line", "column", "textregion").contains(getBodyValue(it)) },
            text = getMockedText(mockedAnnotations),
            iiif = IIIFContext(
                manifest = manifest,
                canvasId = canvasId
            )
        )
    }

    private fun getMockedText(annos: List<Map<String, Any>>): List<String> {
        val anno = annos[0]
        val id = anno["id"]
        log.info("anno[0].id = $id")

        val scanpage = annos.find { getBodyValue(it) == "scanpage" }.orEmpty()
        log.info("scanpage: $scanpage")

        val target = scanpage["target"] as Array<*>
        val textTarget = target.find { (it as HashMap<*, *>)["type"] == "Text" } as HashMap<*, *>
        val textSelector = textTarget["selector"] as HashMap<*, *>
        val start = textSelector["start"]
        val end = textSelector["end"]
        log.info("start=$start, end=$end")

        val mockedText = ArrayList<String>()

        val reader = ResourceLoader.asReader("mock/vol_1728-opening_285.json")
        val json = JSON.parse(reader)
        log.info("json: ${JSON.toString(json)}")
        if (json is Array<*>) {
            for (line in json) {
                if (line is String) {
                    mockedText.add(line)
                }
            }
        }
        return mockedText
    }

    private fun getBodyValue(anno: Map<String, Any>): String? {
        val body = anno["body"]
        return if (body is HashMap<*, *>) body["value"] as String else null
    }

    private fun loadMockAnnotations(): List<Map<String, Any>> {
        val mockedAnnos = ArrayList<Map<String, Any>>()
        for (i in 0..3) {
            val reader = ResourceLoader.asReader("mock/anno-page-$i.json")
            val annoPage = JSON.parse(reader)
            if (annoPage is HashMap<*, *>) {
                val items = annoPage["items"]
                if (items is Array<*>) {
                    @Suppress("UNCHECKED_CAST")
                    items.forEach { if (it is Map<*, *>) mockedAnnos.add(it as Map<String, Any>) }
                } else {
                    log.info("AnnotationPage[\"items\"] not a JSON array, skipping: $items.")
                }
            }
        }
        return mockedAnnos
    }
}