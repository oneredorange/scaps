/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.searchEngine.index

import scaps.api.ValueDef
import scaps.api.TypeRef
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import scaps.api.DocComment
import scaps.api.Module
import scaps.api.UnknownSource

class ValueIndexSpecs extends FlatSpec with Matchers with IndexUtils {
  "the index" should "persist entities and retrieve them by name" in {
    withValueIndex("""
      package p

      object O{
        val v = 1
      }
      """) { index =>
      val v = ValueDef("p.O.v", Nil, TypeRef.Int(), DocComment.empty, Set(ValueDef.Static))
      findByName(index)("v") should ((have size 1) and contain(v))
    }
  }

  it should "tokenize full qualified names" in {
    withValueIndex("""
      package pkg

      object Obj{
        val value = 1
      }
      """) { index =>
      val value = ValueDef("pkg.Obj.value", Nil, TypeRef.Int(), DocComment.empty, Set(ValueDef.Static))

      findByName(index)("value") should contain(value)
      findByName(index)("obj") should contain(value)
      findByName(index)("pkg") should contain(value)
      findByName(index)("Pkg Obj") should contain(value)
    }
  }

  it should "tokenize names on case changes" in {
    withValueIndex("""
      package somePkg

      object AnotherObj{
        val myValue = 1
      }
      """) { index =>
      val value = ValueDef("somePkg.AnotherObj.myValue", Nil, TypeRef.Int(), DocComment.empty, Set(ValueDef.Static))
      findByName(index)("value") should contain(value)
      findByName(index)("another") should contain(value)
      findByName(index)("pkg") should contain(value)
      findByName(index)("another Value") should contain(value)
    }
  }

  it should "tokenize operators" in {
    withValueIndex("""
      package p

      object ::{
        val ++ = 1
      }
      """) { index =>
      val value = ValueDef("p.::.++", Nil, TypeRef.Int(), DocComment.empty, Set(ValueDef.Static))
      findByName(index)("::") should contain(value)
      findByName(index)("++") should contain(value)
    }
  }

  def findByName(index: ValueIndex)(name: String) =
    index.findValuesByName(name).get.map(_.copy(docLink = None, source = UnknownSource))
}
