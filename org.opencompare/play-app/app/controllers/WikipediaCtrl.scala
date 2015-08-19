package controllers

import java.net.{MalformedURLException, URLDecoder, URL}
import java.nio.charset.StandardCharsets

import model.{PCMAPIUtils, Database}
import org.opencompare.api.java.impl.PCMFactoryImpl
import org.opencompare.api.java.{PCMFactory, PCMContainer, PCM}
import org.opencompare.formalizer.extractor.CellContentInterpreter
import org.opencompare.io.wikipedia.io.{WikiTextExporter, MediaWikiAPI, WikiTextLoader, WikiTextTemplateProcessor}
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.Json
import play.api.mvc.{Action, Controller}

import scala.collection.JavaConversions._

/**
 * Created by gbecan on 8/18/15.
 */
class WikipediaCtrl extends Controller {

  val inputParametersForm = Form(
    mapping(
      "url" -> nonEmptyText
    )(WikipediaImportParameters.apply)(WikipediaImportParameters.unapply)
  )

  val outputParametersForm = Form(
    mapping(
      "productAsLines" -> boolean,
      "file" -> text
    )(WikipediaExportParameters.apply)(WikipediaExportParameters.unapply)
  )
  private val pcmFactory: PCMFactory = new PCMFactoryImpl
  private val mediaWikiAPI: MediaWikiAPI = new MediaWikiAPI("wikipedia.org")
  private val wikitextTemplateProcessor: WikiTextTemplateProcessor = new WikiTextTemplateProcessor(mediaWikiAPI)
  private val miner: WikiTextLoader = new WikiTextLoader(wikitextTemplateProcessor)
  private val cellContentInterpreter: CellContentInterpreter = new CellContentInterpreter
  
  private val wikiExporter: WikiTextExporter = new WikiTextExporter(true)

  def importPCMs() = Action { implicit request =>

    val parameters = inputParametersForm.bindFromRequest.get
    val url = parameters.url

    try {
      val pageURL = new URL(url)
      val host = pageURL.getHost
      val language = host.substring(0, host.indexOf('.'))
      var file = URLDecoder.decode(pageURL.getFile, StandardCharsets.UTF_8.name)
      if (file.endsWith("/")) {
        file = file.substring(0, file.length - 1)
      }
      val title = file.substring(file.lastIndexOf('/') + 1)


      // Parse article from Wikipedia
      val code = mediaWikiAPI.getWikitextFromTitle(language, title)

      val pcmContainers = miner.mine(language, code, title).toList

      if (pcmContainers.isEmpty) {
        NotFound("No matrices were found in this Wikipedia page")
      } else {

        // Normalize the matrices
        for (pcmContainer <- pcmContainers) {
          val pcm = pcmContainer.getPcm
          pcm.normalize(pcmFactory)
          cellContentInterpreter.interpretCells(pcm)
        }

        val id: String = Database.INSTANCE.create(pcmContainers.get(0))

        // Serialize result
        val jsonResult: String = Database.INSTANCE.serializePCMContainersToJSON(pcmContainers)

        Ok(jsonResult)
      }
    }
    catch {
      case e: MalformedURLException => NotFound("URL is not a valid Wikipedia page")
      case e: Exception => NotFound("The page has not been found.") // TODO: manage the different kind of exceptions
    }

  }

  def exportPCM() = Action { implicit request =>
    val parameters = outputParametersForm.bindFromRequest.get
    val pcmJSON = Json.parse(parameters.pcm)

    val container = PCMAPIUtils.createContainers(pcmJSON).head
    container.getMetadata.setProductAsLines(parameters.productAsLines)

    val wikitext = wikiExporter.export(container)
    
    Ok(wikitext)
  }

}


case class WikipediaImportParameters(
                                url : String
                                )

case class WikipediaExportParameters(
                                productAsLines : Boolean,
                                pcm : String
                                )