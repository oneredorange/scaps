package scaps.searchEngine.index

import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scaps.searchEngine.ApiTypeQuery._
import scaps.webapi.Covariant

class TypeFingerprintQuerySpecs extends FlatSpec with Matchers {
  import scaps.searchEngine.index.TypeFingerprintQuery.FingerprintScorer

  def tpe(name: String, boost: Double) = Type(Covariant, name, boost)

  "a fingerprint scorer" should "score a simple type query" in {
    val scorer = FingerprintScorer(tpe("A", 1))

    scorer.score("+A" :: Nil) should be(1)
    scorer.score("+A" :: "+B" :: Nil) should be(1)
    scorer.score("+B" :: Nil) should be(0)
  }

  it should "score sum queries" in {
    val scorer = FingerprintScorer(
      Sum(
        tpe("A", 1),
        tpe("B", 2)))

    scorer.score("+A" :: Nil) should be(1)
    scorer.score("+A" :: "+B" :: Nil) should be(3)
  }

  it should "score max queries" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1),
        tpe("B", 2)))

    scorer.score("+A" :: Nil) should be(1)
    scorer.score("+B" :: Nil) should be(2)
    scorer.score("+A" :: "+B" :: Nil) should be(2)
    scorer.score("+B" :: "+A" :: Nil) should be(2)
  }

  it should "allow multiple matches on sum queries that are part of a max query" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1),
        Sum(
          tpe("B", 2),
          tpe("C", 0.5))))

    scorer.score("+A" :: "+B" :: "+C" :: Nil) should be(2.5f)
  }

  it should "score repeated types" in {
    val scorer = FingerprintScorer(
      Sum(
        Max(
          tpe("A", 0.9),
          tpe("B", 0.5)),
        tpe("A", 1),
        tpe("A", 1)))

    scorer.score("+A" :: "+A" :: Nil) should be(2f)
    scorer.score("+A" :: "+B" :: Nil) should be(1.5f)
    scorer.score("+A" :: "+A" :: "+A" :: Nil) should be(2.9f)
    scorer.score("+B" :: "+A" :: "+A" :: Nil) should be(2.5f)
  }

  ignore should "score sums with a total higher match" in {
    val scorer = FingerprintScorer(
      Max(
        tpe("A", 1.5),
        Sum(
          tpe("B", 1),
          tpe("C", 1))))

    scorer.score("+A" :: "+B" :: "+C" :: Nil) should be(2)
  }
}