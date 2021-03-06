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

package com.ore.infinium.systems.client

import com.artemis.BaseSystem
import com.artemis.ComponentMapper
import com.artemis.annotations.Wire
import com.artemis.managers.TagManager
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.*
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.IntMap
import com.ore.infinium.OreBlock
import com.ore.infinium.OreSettings
import com.ore.infinium.OreWorld
import com.ore.infinium.components.SpriteComponent
import com.ore.infinium.systems.server.TileLightingSystem
import com.ore.infinium.util.*
import ktx.app.clearScreen
import ktx.assets.file

@Wire
class TileRenderSystem(private val camera: OrthographicCamera,
                       private val fullscreenCamera: Camera,
                       private val oreWorld: OreWorld)
    : BaseSystem(), RenderSystemMarker {
    //indicates if tiles should be drawn, is a debug flag.
    var debugRenderTiles = true
    //false if lighting should be disabled/ignored
    var debugRenderTileLighting = true
    var debugTilesInViewCount: Int = 0

    var blockAtlas: TextureAtlas = TextureAtlas("packed/blocks.atlas")
    var tilesAtlas: TextureAtlas = TextureAtlas("packed/tiles.atlas")

    private val batch: SpriteBatch = SpriteBatch(MAX_SPRITES_PER_BATCH)

    private lateinit var mSprite: ComponentMapper<SpriteComponent>

    private lateinit var clientNetworkSystem: ClientNetworkSystem
    private lateinit var tagManager: TagManager

    // <byte mesh type, string texture name>
    var dirtBlockMeshes: IntMap<String>
    var stoneBlockMeshes: IntMap<String>
    var grassBlockMeshes: IntMap<String>

    val tileAtlasCache = hashMapOf<String, TextureRegion>()

    val tileLightMapFbo: FrameBuffer
    val tileMapFbo: FrameBuffer

    private val tileLightMapBlendShader: ShaderProgram
    private val emptyTexture: Texture

    private val defaultShader: ShaderProgram

    // grabbed by other renderers to mask lighting for them too
    val tileLightMapFboRegion: TextureRegion
    private val tileMapFboRegion: TextureRegion

    init {

        tilesAtlas.regions.forEach { tileAtlasCache[it.name] = it }

        val emptyPixmap = Pixmap(16, 16, Pixmap.Format.RGBA8888)
        emptyPixmap.setColor(Color.WHITE)
        emptyPixmap.fill()
        emptyTexture = Texture(emptyPixmap)

        //todo obviously, we can replace this map and lookup with something cheaper, i bet.
        //it's actually only used to fetch the string which then we will fetch from the texture atlas
        //and we're actually not supposed to be calling the texture atlas get functions so often..
        //since they are not cached.

        //dirt 16 and beyond are transition things.
        val dirtMax = 25
        dirtBlockMeshes = IntMap<String>(dirtMax + 1)
        for (i in 0..dirtMax) {
            val formatted = "dirt-%02d".format(i)
            dirtBlockMeshes.put(i, formatted)
        }

        //18+ are transition helpers
        val grassMax = 31
        grassBlockMeshes = IntMap<String>(grassMax + 1)
        for (i in 0..grassMax) {
            val formatted = "grass-%02d".format(i)
            grassBlockMeshes.put(i, formatted)
        }

        val stoneMax = 30
        stoneBlockMeshes = IntMap<String>(stoneMax + 1)
        for (i in 0..stoneMax) {
            val formatted = "stone-%02d".format(i)
            stoneBlockMeshes.put(i, formatted)
        }

        defaultShader = batch.shader

        tileMapFbo = FrameBuffer(Pixmap.Format.RGBA8888,
                                 OreSettings.width,
                                 OreSettings.height, false)

        tileLightMapFbo = FrameBuffer(Pixmap.Format.RGBA8888,
                                      Gdx.graphics.backBufferWidth,
                                      Gdx.graphics.backBufferHeight, false)

        val tileLightMapBlendVertex = file("shaders/tileLightMapBlend.vert").readString()
        val tileLightMapBlendFrag = file("shaders/tileLightMapBlend.frag").readString()

        tileLightMapBlendShader = ShaderProgram(tileLightMapBlendVertex, tileLightMapBlendFrag)
        check(tileLightMapBlendShader.isCompiled) { "tileLightMapBlendShader compile failed: ${tileLightMapBlendShader.log}" }

        tileLightMapBlendShader.use {
            tileLightMapBlendShader.setUniformi("u_lightmap", 1)
        }

        tileMapFboRegion = TextureRegion(tileMapFbo.colorBufferTexture).flipY()

        tileLightMapFboRegion = TextureRegion(tileLightMapFbo.colorBufferTexture).flipY()
    }

    override fun processSystem() {
        if (!clientNetworkSystem.connected) {
            return
        }

        if (!debugRenderTiles) {
            return
        }

        batch.projectionMatrix = camera.combined

        renderTiles()
        renderLightMap()
        renderBlendLightMapOnTop()
    }

    fun renderTiles() {
        val tilesInView = tilesInView()

        batch.shader = defaultShader
        tileMapFbo.begin()

        clearScreen2(.15f, .15f, .15f, 0f)

        batch.begin()

        debugTilesInViewCount = 0

        for (y in tilesInView.top until tilesInView.bottom) {
            loop@ for (x in tilesInView.left until tilesInView.right) {

                val blockType = oreWorld.blockType(x, y)
                val blockMeshType = oreWorld.blockMeshType(x, y)
                val blockWallType = oreWorld.blockWallType(x, y)

                val blockLightLevel = debugLightLevel(x, y)

                val tileX = x.toFloat()
                val tileY = y.toFloat()

                val lightValue = computeLightValueColor(blockLightLevel)

                var shouldDrawForegroundTile = true
                if (blockType == OreBlock.BlockType.Air.oreValue) {
                    shouldDrawForegroundTile = false
                    if (blockWallType == OreBlock.WallType.Air.oreValue) {
                        //we can skip over entirely empty blocks
                        continue@loop
                    }
                }

                if (blockWallType != OreBlock.WallType.Air.oreValue) {
                    drawWall(lightValue, tileX, tileY, blockType, blockMeshType, blockWallType)
                }

                //liquid render system handles this, skip foreground tiles that are liquid
                if (oreWorld.isBlockTypeLiquid(blockType)) {
                    continue@loop
                }

                if (shouldDrawForegroundTile) {
                    drawForegroundTile(lightValue, tileX, tileY, blockType, x, y, blockMeshType)
                }

                ++debugTilesInViewCount
            }
        }

        batch.end()
        //oreWorld.dumpFboAndExitAfterMs()
        tileMapFbo.end()
    }

    private fun renderLightMap() {
        //hack
        fun setLightsRow(y: Int, lightLevel: Byte) {
            for (x in 0 until oreWorld.worldSize.width) {
                oreWorld.setBlockLightLevel(x, y, lightLevel)
            }
        }

        fun setLightsColumn(x: Int, lightLevel: Byte) {
            for (y in 0 until oreWorld.worldSize.height) {
                oreWorld.setBlockLightLevel(x, y, lightLevel)
            }
        }

        //      setLightsRow(50, 5)
        //       setLightsRow(20, 2)
//        setLightsRow(60, 3)
        //setLightsRow(80, 4)
        //setLightsColumn(500, 4)
        //  setLightsRow(90, 7)
        //   setLightsRow(10, 7)
        //    setLightsRow(0, 7)
        //     setLightsRow(100, 7)

        batch.shader = defaultShader
        tileLightMapFbo.begin()

        clearScreen(0f, 0f, 0f)

        batch.begin()

        val tilesInView = tilesInView()
        for (y in tilesInView.top until tilesInView.bottom) {
            loop@ for (x in tilesInView.left until tilesInView.right) {
                val blockType = oreWorld.blockType(x, y)
                val blockMeshType = oreWorld.blockMeshType(x, y)
                val tileX = x.toFloat()
                val tileY = y.toFloat()

                //val foregroundTileRegion = tileAtlasCache["dirt-00"]
                val lightLevel = debugLightLevel(x, y)
                val lightValue = computeLightValueColor(lightLevel)
                batch.setColor(lightValue, lightValue, lightValue, 1f)

                //hack magenta overriide for debug
                //if (lightLevel != TileLightingSystem.MAX_TILE_LIGHT_LEVEL) {
                //batch.setColor(0f, lightValue, 0f, 1f)
                //}

                batch.draw(emptyTexture, tileX, tileY + 1, 1f, -1f)

            }
        }

//        oreWorld.dumpFboAndExitAfterMs(15000)
        batch.end()
        tileLightMapFbo.end()
    }

    private fun renderBlendLightMapOnTop() {
        batch.shader = tileLightMapBlendShader

        Gdx.gl20.glActiveTexture(GL20.GL_TEXTURE0 + 1)
        tileLightMapFbo.colorBufferTexture.bind(1)
        Gdx.gl20.glActiveTexture(GL20.GL_TEXTURE0)

        batch.setColor(1f, 1f, 1f, 1f)

        //tileLightMapFbo.colorBufferTexture.bind()

        batch.projectionMatrix = fullscreenCamera.combined
        batch.begin()

        batch.draw(tileMapFboRegion, 0f, 0f, OreSettings.width.toFloat(),
                   OreSettings.height.toFloat())
        batch.end()
    }

    class TilesInView(val left: Int, val right: Int, val top: Int, val bottom: Int)

    fun tilesInView(): TilesInView {
        val sprite = mSprite.get(tagManager.getEntityId(OreWorld.s_mainPlayer))

        val playerPosition = Vector3(sprite.sprite.x, sprite.sprite.y, 0f)
        val tilesBeforeX = playerPosition.x.toInt()
        val tilesBeforeY = playerPosition.y.toInt()

        // determine what the size of the tiles are but convert that to our zoom level
        val tileSize = Vector3(1f, 1f, 0f)
        tileSize.mul(camera.combined)

        val tilesInView = (camera.viewportHeight * camera.zoom).toInt()
        val left = (tilesBeforeX - tilesInView - 2).coerceAtLeast(0)
        val top = (tilesBeforeY - tilesInView - 2).coerceAtLeast(0)
        val right = (tilesBeforeX + tilesInView + 2).coerceAtMost(oreWorld.worldSize.width)
        val bottom = (tilesBeforeY + tilesInView + 2).coerceAtMost(oreWorld.worldSize.height)

        return TilesInView(left = left, right = right, top = top, bottom = bottom)
    }

    fun computeLightValueColor(blockLightLevel: Byte): Float {
        val res = blockLightLevel.toFloat() / TileLightingSystem.MAX_TILE_LIGHT_LEVEL.toFloat()
        assert(res <= 1f)

        return res
    }

    private fun drawWall(lightValue: Float,
                         tileX: Float,
                         tileY: Float,
                         blockType: Byte,
                         blockMeshType: Byte, blockWallType: Byte) {
        val wallTextureName = dirtBlockMeshes.get(0)
        assert(wallTextureName != null) { "block mesh lookup failure type: $blockMeshType" }
        //fixme of course, for wall drawing, walls should have their own textures
        //batch.setColor(0.5f, 0.5f, 0.5f, 1f)
        //batch.setColor(1.0f, 0f, 0f, 1f)
//        batch.setColor(lightValue, lightValue, lightValue, 1f)

        //offset y to flip orientation around to normal
        val regionWall = tileAtlasCache[wallTextureName]
        batch.draw(regionWall, tileX, tileY + 1, 1f, -1f)

        batch.setColor(1f, 1f, 1f, 1f)
    }

    private fun drawForegroundTile(lightValue: Float,
                                   tileX: Float,
                                   tileY: Float,
                                   blockType: Byte,
                                   x: Int, y: Int, blockMeshType: Byte) {
        //if (blockLightLevel != 0.toByte()) {
        //batch.setColor(lightValue, lightValue, lightValue, 1f)
        //                   } else {
        //                      batch.setColor(1f, 1f, 1f, 1f)
        //                 }

        var resetColor = false

        val textureName = findTextureNameForBlock(x, y, blockType, blockMeshType)

        val foregroundTileRegion = tileAtlasCache[textureName]
        assert(foregroundTileRegion != null) { "texture region for tile was null. textureName: ${textureName}" }

        //offset y to flip orientation around to normal
        batch.draw(foregroundTileRegion, tileX, tileY + 1, 1f, -1f)

        if (resetColor) {
//            batch.setColor(1f, 1f, 1f, 1f)
        }
    }

    fun debugLightLevel(x: Int, y: Int): Byte {
        if (debugRenderTileLighting) {
            return oreWorld.blockLightLevel(x, y)
        } else {
            return TileLightingSystem.MAX_TILE_LIGHT_LEVEL
        }
    }

    fun findTextureNameForBlock(x: Int, y: Int, blockType: Byte, blockMeshType: Byte): String {
        val blockWallType = oreWorld.blockWallType(x, y)

        //String textureName = World.blockAttributes.get(block.type).textureName;
        val hasGrass = oreWorld.blockHasFlag(x, y, OreBlock.BlockFlags.GrassBlock)

        var textureName: String? = null
        when (blockType) {
            OreBlock.BlockType.Dirt.oreValue -> {

                if (hasGrass) {
                    textureName = grassBlockMeshes.get(blockMeshType.toInt())
                    assert(textureName != null) { "block mesh lookup failure" }
                } else {
                    textureName = dirtBlockMeshes.get(blockMeshType.toInt())
                    assert(textureName != null) { "block mesh lookup failure type: $blockMeshType" }
                }
            }

            OreBlock.BlockType.Stone.oreValue -> {
                textureName = stoneBlockMeshes.get(blockMeshType.toInt())
                assert(textureName != null) { "block mesh lookup failure type: $blockMeshType" }

            }

            OreBlock.BlockType.Air.oreValue -> {
                //not drawn/handled by this function at all
                textureName = "(air) no texture"
            }

            OreBlock.BlockType.Coal.oreValue -> {
                textureName = "coal"
            }

            OreBlock.BlockType.Copper.oreValue -> {
                textureName = "copper-00"
            }

            OreBlock.BlockType.Uranium.oreValue -> {
                textureName = "uranium"
            }

            OreBlock.BlockType.Diamond.oreValue -> {
                textureName = "diamond"
            }

            OreBlock.BlockType.Iron.oreValue -> {
                textureName = "iron"
            }

            OreBlock.BlockType.Sand.oreValue -> {
                textureName = "sand"
            }

            OreBlock.BlockType.Bedrock.oreValue -> {
                textureName = "bedrock"
            }

            OreBlock.BlockType.Silver.oreValue -> {
                textureName = "silver"
            }

            OreBlock.BlockType.Gold.oreValue -> {
                textureName = "gold"
            }

        //liquids not handled here, but other function

            else
            -> {
                assert(false) { "unhandled block blockType: $blockType" }
            }
        }

        if (textureName == null) {
            error("tile renderer block texture lookup failed. not found in mapping. blockTypeName: ${OreBlock.nameOfBlockType(
                    blockType)}")
        }

        return textureName
    }
}


