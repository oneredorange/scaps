package scaps.searchEngine.index

import scaps.featureExtraction.ExtractionUtils
import scaps.webapi.TypeDef
import scaps.webapi.ValueDef
import scaps.settings.Settings
import scaps.utils.using

import org.apache.lucene.store.Directory
import org.apache.lucene.store.RAMDirectory

trait IndexUtils extends ExtractionUtils {

  val settings = Settings.fromApplicationConf

  def withDir[T](f: Directory => T): T =
    using(new RAMDirectory)(f).get

  def withValueIndex[T](f: ValueIndex => T): T =
    withDir { dir =>
      val index = new ValueIndex(dir, settings)
      f(index)
    }

  def withValueIndex[T](sources: String*)(f: ValueIndex => T): T =
    withValueIndex { index =>
      val entities = sources.toStream.flatMap(extractAll).collect { case t: ValueDef => t }
      index.addEntities(entities).get
      f(index)
    }

  def withTypeIndex[T](f: TypeIndex => T): T =
    withDir { dir =>
      val index = new TypeIndex(dir, settings)
      f(index)
    }

  def withTypeIndex[T](sources: String*)(f: TypeIndex => T): T =
    withTypeIndex { index =>
      val entities = sources.flatMap(extractAll).collect { case t: TypeDef => t }
      index.addEntities(entities).get
      f(index)
    }

  def withModuleIndex[T](f: ModuleIndex => T): T =
    withDir { dir =>
      val index = new ModuleIndex(dir)
      f(index)
    }

  def withViewIndex[T](f: ViewIndex => T): T =
    withDir { dir =>
      f(new ViewIndex(dir))
    }
}
