package scala.tools.apiSearch.index

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scala.tools.apiSearch.model._
import org.apache.lucene.store.RAMDirectory
import scala.tools.apiSearch.utils.using
import scala.tools.apiSearch.featureExtraction.ExtractionUtils

class ClassIndexSpecs extends FlatSpec with Matchers with ExtractionUtils {
  "the class index" should "persist class entities and retrieve them by name" in {
    withClassIndex("""
      package p

      class C{
      }
      """) { index =>
      index.findClassByName("p.C").get should be(Option(ClassEntity("p.C", Nil, List(TypeEntity.anyRef, TypeEntity.any))))
    }
  }

  def withClassIndex(f: ClassIndex => Unit): Unit = {
    using(new RAMDirectory) { termsDir =>
      val index = new ClassIndex(termsDir)
      f(index)
    }
  }

  def withClassIndex(sources: String*)(f: ClassIndex => Unit): Unit = withClassIndex { index =>
    val entities = sources.toStream.flatMap(extractAll).collect { case t: ClassEntity => t }
    index.addEntities(entities)
    f(index)
  }
}