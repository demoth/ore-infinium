package com.ore.infinium

import java.io.FileOutputStream

class WorldIO(val oreWorld: OreWorld) {
    fun loadWorld() {
    }

    val FILESAVE_BASE_PATH = "../saveData/"

    fun saveWorld() {
        val fs = FileOutputStream(FILESAVE_BASE_PATH + "worldsave.save").use { fs ->
            val blocks = PbBlocks.newBuilder()
            for (y in 0 until OreWorld.WORLD_SIZE_X) {
                for (x in 0 until OreWorld.WORLD_SIZE_X) {
                    val blockType = oreWorld.blockType(x,y)
                    blocks.addBlockTypes(blockType.toInt())
                }
            }

            val b = PbWorldSave.newBuilder().setBlocks(blocks).build()

            b.writeTo(fs)

            fs.flush()
            fs.close()
        }
    }

}