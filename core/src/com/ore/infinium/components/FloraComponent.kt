/**
MIT License

Copyright (c) 2016 Shaun Reich <sreich02@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package com.ore.infinium.components

import com.artemis.Component
import com.ore.infinium.util.ExtendedComponent

class FloraComponent : Component(), ExtendedComponent<FloraComponent> {
    /**
     * Number of separate flora objects dropped,
     * when this flora entity is destroyed.
     * e.g a tree exploding into a bunch of different objects when it gets destroyed
     * For example, if this value is 2 on a tree, a tree would explode, drop 2 objects
     * each with @see stackSizePerDrop number of items within it
     */
    var numberOfDropsWhenDestroyed = 1
    var stackSizePerDrop = 2

    enum class FloraType {
        Tree,
        Vine
    }

    enum class TreeSize {
        Small,
        Medium,
        Large
    }

    /**
     * determines if the item component is the same, in other words,
     * if it is the same kind of item. to determine if it can merge/combine
     */
    override fun canCombineWith(other: FloraComponent) = true

    override fun copyFrom(other: FloraComponent) = throw TODO("function not yet implemented")
}
