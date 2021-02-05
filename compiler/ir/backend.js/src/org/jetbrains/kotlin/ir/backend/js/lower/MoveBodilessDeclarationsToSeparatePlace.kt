/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.ir.addChild
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.backend.js.utils.getJsModule
import org.jetbrains.kotlin.ir.backend.js.utils.getJsQualifier
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrFileSymbolImpl
import org.jetbrains.kotlin.ir.util.constructedClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

private val BODILESS_BUILTIN_CLASSES = listOf(
    "kotlin.String",
    "kotlin.Nothing",
    "kotlin.Array",
    "kotlin.Any",
    "kotlin.ByteArray",
    "kotlin.CharArray",
    "kotlin.ShortArray",
    "kotlin.IntArray",
    "kotlin.LongArray",
    "kotlin.FloatArray",
    "kotlin.DoubleArray",
    "kotlin.BooleanArray",
    "kotlin.Boolean",
    "kotlin.Byte",
    "kotlin.Short",
    "kotlin.Int",
    "kotlin.Float",
    "kotlin.Double",
    "kotlin.Function"
).map { FqName(it) }.toSet()

private fun isBuiltInClass(declaration: IrDeclaration): Boolean =
    declaration is IrClass && declaration.fqNameWhenAvailable in BODILESS_BUILTIN_CLASSES

private val JsPackage = FqName("kotlin.js")

private val JsIntrinsicFqName = FqName("kotlin.js.JsIntrinsic")

private fun isIntrinsic(declaration: IrDeclaration): Boolean =
    declaration is IrSimpleFunction && (declaration.parent as? IrPackageFragment)?.fqName == JsPackage &&
            declaration.annotations.any { it.symbol.owner.constructedClass.fqNameWhenAvailable == JsIntrinsicFqName }

fun moveBodilessDeclarationsToSeparatePlace(context: JsIrBackendContext, moduleFragment: IrModuleFragment) {
    MoveBodilessDeclarationsToSeparatePlaceLowering(context).let { moveBodiless ->
        moduleFragment.files.forEach {
            validateIsExternal(it)
            moveBodiless.lower(it)
        }
    }
}

class MoveBodilessDeclarationsToSeparatePlaceLowering(private val context: JsIrBackendContext) : DeclarationTransformer {

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        val irFile = declaration.parent as? IrFile ?: return null

        val externalPackageFragment by lazy {
            context.externalPackageFragment.getOrPut(irFile.symbol) {
                IrFileImpl(fileEntry = irFile.fileEntry, fqName = irFile.fqName, symbol = IrFileSymbolImpl(), module = irFile.module).also {
                    it.annotations += irFile.annotations
                }
            }
        }

        if (irFile.getJsModule() != null || irFile.getJsQualifier() != null) {
            externalPackageFragment.declarations += declaration
            declaration.parent = externalPackageFragment

            context.packageLevelJsModules += externalPackageFragment

            declaration.collectAllExternalDeclarations()

            return emptyList()
        } else {
            if (declaration !is IrDeclarationWithName) return null

            if (isBuiltInClass(declaration) || isIntrinsic(declaration)) {
                context.bodilessBuiltInsPackageFragment.addChild(declaration)
                declaration.collectAllExternalDeclarations()

                return emptyList()
            } else if (declaration.isEffectivelyExternal()) {
                if (declaration.getJsModule() != null)
                    context.declarationLevelJsModules.add(declaration)

                externalPackageFragment.declarations += declaration
                declaration.parent = externalPackageFragment

                declaration.collectAllExternalDeclarations()

                return emptyList()
            }

            return null
        }
    }

    private fun IrDeclaration.collectAllExternalDeclarations() {
        this.accept(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitDeclaration(declaration: IrDeclaration) {
                context.externalDeclarations.add(declaration)
                super.visitDeclaration(declaration)
            }
        }, null)
    }
}

fun validateIsExternal(packageFragment: IrPackageFragment) {
    for (declaration in packageFragment.declarations) {
        validateNestedExternalDeclarations(declaration, (declaration as? IrPossiblyExternalDeclaration)?.isExternal ?: false)
    }
}


fun validateNestedExternalDeclarations(declaration: IrDeclaration, isExternalTopLevel: Boolean) {
    fun IrPossiblyExternalDeclaration.checkExternal() {
        if (isExternal != isExternalTopLevel) {
            error("isExternal validation failed for declaration ${declaration.render()}")
        }
    }

    if (declaration is IrPossiblyExternalDeclaration) {
        declaration.checkExternal()
    }
    if (declaration is IrProperty) {
        declaration.getter?.checkExternal()
        declaration.setter?.checkExternal()
        declaration.backingField?.checkExternal()
    }
    if (declaration is IrClass) {
        declaration.declarations.forEach {
            validateNestedExternalDeclarations(it, isExternalTopLevel)
        }
    }
}

