package org.jetbrains.plugins.scala
package lang
package psi
package stubs
package impl

import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.{IStubElementType, StubBase, StubElement}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportSelectors

/**
  * User: Alexander Podkhalyuzin
  * Date: 20.06.2009
  */
class ScImportSelectorsStubImpl(parent: StubElement[_ <: PsiElement],
                                elementType: IStubElementType[_ <: StubElement[_ <: PsiElement], _ <: PsiElement],
                                override val hasWildcard: Boolean)
  extends StubBase[ScImportSelectors](parent, elementType) with ScImportSelectorsStub