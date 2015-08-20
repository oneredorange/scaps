package scaps.searchEngine.index

import scala.util.Try
import scala.collection.JavaConverters._
import org.apache.lucene.analysis.core.KeywordAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause.Occur
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import scaps.settings.Settings
import scaps.webapi.TypeDef
import scaps.webapi.Covariant
import scaps.webapi.Module
import scaps.webapi.TypeRef
import scala.annotation.tailrec

/**
 * Persists class entities and provides lookup for typeDefs by name.
 *
 * This index is mainly used for fast access to class hierarchies for query building.
 */
class TypeIndex(val dir: Directory, settings: Settings) extends Index[TypeDef] {
  import TypeIndex._

  val analyzer = new KeywordAnalyzer

  def addEntities(entities: Seq[TypeDef]): Try[Unit] = Try {
    val distinctEntities = entities.distinct
    val indexedTypeDefs = allTypeDefs().get

    val entitiesWithModules = distinctEntities.map { cls =>
      indexedTypeDefs.find(_.name == cls.name)
        .fold(cls) { indexedCls =>
          cls.copy(referencedFrom = cls.referencedFrom ++ indexedCls.referencedFrom)
        }
    }

    withWriter { writer =>
      entitiesWithModules.foreach { entity =>
        val doc = toDocument(entity)
        writer.updateDocument(new Term(fields.name, entity.name), doc)
      }
    }.get
  }

  def replaceAllEntities(entities: Seq[TypeDef]): Try[Unit] =
    withWriter { writer =>
      val docs = entities.map(toDocument)
      writer.deleteAll()
      writer.addDocuments(docs.asJavaCollection)
    }

  def deleteEntitiesIn(module: Module): Try[Unit] = Try {
    val q = new TermQuery(new Term(fields.modules, module.moduleId))
    val typeDefsInModule = search(q).get

    withWriter { writer =>
      typeDefsInModule.foreach { cls =>
        val clsTerm = new Term(fields.name, cls.name)

        val clsWithoutModule = cls.copy(referencedFrom = cls.referencedFrom - module)

        if (clsWithoutModule.referencedFrom.isEmpty) {
          writer.deleteDocuments(clsTerm)
        } else {
          writer.updateDocument(clsTerm, toDocument(clsWithoutModule))
        }
      }
    }.get
  }

  /**
   * Searches for class entities whose last parts of the full qualified name are `suffix`
   * and accept `noArgs` type parameters.
   */
  def findTypeDefsBySuffix(suffix: String, moduleIds: Set[String] = Set()): Try[Seq[TypeDef]] = {
    val query = new BooleanQuery()
    query.add(new TermQuery(new Term(fields.suffix, suffix)), Occur.MUST)

    if (!moduleIds.isEmpty) {
      val moduleQuery = new BooleanQuery()

      for (moduleId <- moduleIds) {
        moduleQuery.add(new TermQuery(new Term(fields.modules, moduleId)), Occur.SHOULD)
      }

      query.add(moduleQuery, Occur.MUST)
    }

    search(query)
  }

  def findTypeDef(name: String): Try[Option[TypeDef]] = Try {
    search(new TermQuery(new Term(fields.name, name))).get.headOption
  }

  def allTypeDefs(): Try[Seq[TypeDef]] =
    search(new MatchAllDocsQuery)

  override def toDocument(entity: TypeDef): Document = {
    val doc = new Document

    doc.add(new TextField(fields.name, entity.name, Field.Store.YES))

    for (suffix <- suffixes(entity.name)) {
      doc.add(new TextField(fields.suffix, suffix, Field.Store.NO))
    }

    doc.add(new StoredField(fields.entity, upickle.write(entity)))

    for (module <- entity.referencedFrom) {
      doc.add(new TextField(fields.modules, module.moduleId, Field.Store.YES))
    }

    doc
  }

  private def suffixes(name: String): List[String] = name match {
    case "" => Nil
    case s =>
      s.indexWhere(c => c == '.' || c == '#') match {
        case -1  => s :: Nil
        case idx => s :: suffixes(name.drop(idx + 1))
      }
  }

  override def toEntity(doc: Document): TypeDef = {
    val json = doc.getValues(fields.entity)(0)

    upickle.read[TypeDef](json)
  }
}

object TypeIndex {
  object fields {
    val name = "name"
    val suffix = "suffix"
    val entity = "entity"
    val modules = "modules"
  }
}