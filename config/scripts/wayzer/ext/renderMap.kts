@file:Depends("wayzer/user/lang", "玩家语言权限")
@file:Depends("coreLibrary/lang", "读取 mindustrySourceDir 配置")

package wayzer.ext

import arc.files.Fi
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.PixmapIO
import arc.graphics.g2d.TextureAtlas
import arc.math.Mathf
import arc.math.geom.Geometry
import arc.math.geom.Point2
import arc.util.Log
import arc.util.Strings
import cf.wayzer.scriptAgent.util.Services
import coreLibrary.LangService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.world.Block
import mindustry.world.blocks.environment.Cliff
import mindustry.world.blocks.environment.Floor
import mindustry.world.blocks.environment.OreBlock
import mindustry.world.blocks.environment.OverlayFloor
import mindustry.world.blocks.environment.Prop
import mindustry.world.blocks.environment.StaticWall
import mindustry.world.blocks.TileBitmask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

name = "全地图截图（纯 CPU 渲染）"
// ==================== 路径 ====================
/** 贴图图集目录: config/renderSprites/ (需 sprites.aatls + sprites.png ~ sprites4.png) */
/** 源码根目录(mindustrySourceDir, 与 lang.kts 同一配置): 提供 assets-raw 散图贴图源; 无则回退图集 */
private val sourceDir: Fi? get() {
    val dir = try { Services.get<LangService>().get().mindustrySourceDir.trim() } catch (e: Throwable) { "" }
    return if (dir.isEmpty()) null else Fi(dir)
}
private val outDir: Fi get() = Vars.dataDirectory.child("renderMap").apply { mkdirs() }
val tsRender = 32 // 图集每格像素(高清图集 32px = 世界 8 单位)

// ==================== 图集元信息 ====================
/** page>=0 = 图集页内子区域; page==-1 且 file!=null = 源码散图整文件; 生成物(file=null,存 pixCache) */
private class RInfo(val name: String, val page: Int, val left: Int, val top: Int, var w: Int, var h: Int, val file: Fi? = null)

private class Atlas {
    val regions = HashMap<String, RInfo>()
    val pageFis = ArrayList<Fi>()
    @Volatile var pageCount = 0
    private val pixCache = HashMap<String, Pixmap>()
    private val pageCache = HashMap<Int, Pixmap>()
    var rawMode = false
    private val stencil: RInfo? get() = regions["edge-stencil"]

    /** 定位贴图源: 优先源码 assets-raw 散图, 回退 config/renderSprites 图集 */
    fun parse(srcDir: Fi?) {
        regions.clear(); pageFis.clear(); pixCache.clear(); pageCache.clear()
        pageCount = 0
        rawMode = false
        val raw = srcDir?.child("core/assets-raw/sprites")
        if (raw != null && raw.child("blocks").exists()) {
            scan(raw)
            rawMode = true
            Log.info("[renderMap] 使用源码贴图源: @ (@ region)", raw.file(), regions.size)
            return
        }
        // fallback: 图集
        val dir = Vars.dataDirectory.child("renderSprites")
        val aatls = dir.child("sprites.aatls").file()
        if (!aatls.exists()) throw RuntimeException("贴图源不可用: 无 mindustrySourceDir(${srcDir?.file()}) 的 assets-raw, 也无图集 ${dir.file()}")
        val data = TextureAtlas.TextureAtlasData(Fi(aatls), Fi(dir.file()), false)
        val pages = data.getPages()
        pageCount = pages.size
        for (p in pages) pageFis.add(p.textureFile)
        for (r in data.getRegions()) {
            val idx = pages.indexOf(r.page)
            regions[r.name] = RInfo(r.name, idx, r.left, r.top, r.width, r.height)
        }
        Log.info("[renderMap] 使用图集贴图源: @ region / @ 页", regions.size, pageCount)
    }

    private fun scan(dir: Fi) {
        for (f in dir.list()) {
            if (f.isDirectory) scan(f)
            else if (f.name().endsWith(".png") && !f.name().startsWith(".")) {
                val key = f.nameWithoutExtension()
                if (!regions.containsKey(key)) {
                    regions[key] = RInfo(key, -1, 0, 0, 0, 0, f)
                }
            }
        }
    }

    fun region(name: String): RInfo? = regions[name]

    /** 取 region 的像素: 散图整文件 / 图集页 / 生成物(按 name 缓存) */
    fun pix(r: RInfo): Pixmap {
        if (r.file != null) {
            return pixCache.getOrPut(r.name) {
                Pixmap(r.file).also { if (r.w == 0) { r.w = it.width; r.h = it.height } }
            }
        }
        if (r.page >= 0) {
            return pageCache.getOrPut(r.page) { Pixmap(pageFis[r.page]) }
        }
        return pixCache[r.name] ?: throw RuntimeException("region 无像素源: ${r.name}")
    }

    /**
     * floor 的 3x3 edge 图: 优先现成 <name>-edge; 散图模式用 edge-stencil 与主图相乘生成(复刻 Floor.createIcons).
     * @return 3x3 裁切源的 RInfo(整图), 或 null
     */
    fun edgeBase(floorName: String, mainRegion: RInfo?): RInfo? {
        regions[floorName + "-edge"]?.let { return it }
        if (!rawMode) return null
        val stel = stencil ?: return null
        val main = mainRegion ?: return null
        if (main.w != 32 || main.h != 32 || stel.w != 96 || stel.h != 96) return null
        val key = "edge-gen-" + floorName
        if (pixCache.containsKey(key)) return regions[key]
        val sp = pix(stel); val mp = pix(main)
        val gen = Pixmap(stel.w, stel.h)
        for (y in 0 until stel.h) {
            for (x in 0 until stel.w) {
                val se = sp.get(x, y)
                val m = mp.get(x % 32, y % 32)
                gen.set(x, y, rgbo(
                    chR(se) * chR(m) / 255,
                    chG(se) * chG(m) / 255,
                    chB(se) * chB(m) / 255,
                    minOf(chA(se), chA(m))
                ))
            }
        }
        pixCache[key] = gen
        val inf = RInfo(key, -1, 0, 0, stel.w, stel.h)
        regions[key] = inf
        return inf
    }
}

private val atlas = Atlas()

// ==================== 世界快照 ====================
private class BuildSnap(val x: Int, val y: Int, val blockId: Int, val rot: Int, val team: Int)

private class Snap(
    val w: Int, val h: Int,
    val floors: IntArray, val overlays: IntArray,
    val blocks: IntArray, val datas: IntArray,
    val builds: ArrayList<BuildSnap>,
    val buildPos: HashSet<Int>,
    val buildByTile: HashMap<Int, BuildSnap>
)

/** 主线程采集世界数据到扁平数组(避免后台线程读 world 的并发问题) */
private fun takeSnap(): Snap {
    val world = Vars.world
    val w = world.width(); val h = world.height()
    val n = w * h
    val floors = IntArray(n); val overlays = IntArray(n)
    val blocks = IntArray(n); val datas = IntArray(n)
    val builds = ArrayList<BuildSnap>()
    val buildPos = HashSet<Int>((n / 8).coerceAtLeast(16))
    val buildByTile = HashMap<Int, BuildSnap>((n / 8).coerceAtLeast(16))
    // 由远(屏幕上方=世界 y 大)到近遍历, 保证建筑按远近正确遮挡
    for (ty in h - 1 downTo 0) {
        for (tx in 0 until w) {
            val t = world.tile(tx, ty) ?: continue
            val i = ty * w + tx
            floors[i] = t.floor().id.toInt()
            overlays[i] = t.overlay().id.toInt()
            blocks[i] = t.block().id.toInt()
            datas[i] = t.data.toInt()
            val b = t.build
            // 多格建筑每个 tile 的 build 都非空, 用 build.tile 字段判断原点, 避免每格各画一次
            if (b != null && b.tile === t) {
                val snapBuild = BuildSnap(t.x.toInt(), t.y.toInt(), t.block().id.toInt(), b.rotation, b.team.id)
                builds.add(snapBuild)
                buildPos.add(i)
                buildByTile[i] = snapBuild
            }
        }
    }
    return Snap(w, h, floors, overlays, blocks, datas, builds, buildPos, buildByTile)
}

// ==================== 像素操作 (arc Pixmap.get() 返回 RGBA packed: r 最高字节, alpha 最低字节) ====================
private inline fun chR(c: Int) = (c ushr 24) and 0xff
private inline fun chG(c: Int) = (c ushr 16) and 0xff
private inline fun chB(c: Int) = (c ushr 8) and 0xff
private inline fun chA(c: Int) = c and 0xff
private inline fun rgbo(r: Int, g: Int, b: Int, a: Int) = (r shl 24) or (g shl 16) or (b shl 8) or a

private inline fun packSeed(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)

// ==================== 渲染器 ====================
private class Renderer(val snap: Snap, val scale: Int, val out: Pixmap) {
    class FloorInfo(
        val variants: Int, val tilingVariants: Int,
        val autotile: Boolean, val autotileMidVariants: Int,
        val blendId: Int, val isLiquid: Boolean, val overlayAlpha: Float,
        val drawEdgeIn: Boolean, val drawEdgeOut: Boolean,
        val cacheLayerOrd: Int,
        val edgeGroup: String? // blendGroup 名(存在可用的 edge 融合原料则非空)
    )

    private val floorInfos = HashMap<Int, FloorInfo>()
    private val blockVariants = HashMap<Int, Int>()
    private val blockRotate = HashMap<Int, Boolean>()
    private val blockFills = HashMap<Int, Boolean>()

    private fun floorInfo(fid: Int): FloorInfo? {
        floorInfos[fid]?.let { return it }
        val f = Vars.content.block(fid) as? Floor ?: return null
        val inf = FloorInfo(
            f.variants, f.tilingVariants, f.autotile, f.autotileMidVariants,
            f.blendId, f.isLiquid, f.overlayAlpha,
            f.drawEdgeIn, f.drawEdgeOut, f.cacheLayer.id,
            if (atlas.region(f.name + "-edge") != null || atlas.region("edge-stencil") != null) f.blendGroup.name else null
        )
        floorInfos[fid] = inf
        return inf
    }

    private fun blockMeta(bid: Int): Pair<Int, Boolean> {
        blockVariants[bid]?.let { return it to (blockRotate[bid] ?: false) }
        val b = Vars.content.block(bid) ?: return 0 to false
        val v = b.variants
        val r = b.rotate && b.rotateDraw
        blockVariants[bid] = v; blockRotate[bid] = r
        return v to r
    }

    private fun blockFills(bid: Int): Boolean = blockFills[bid] ?: (Vars.content.block(bid)?.fillsTile ?: false).also { blockFills[bid] = it }

    private val missingRegions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ---- 确定性地形变体 (与客户端一致) ----
    private fun variantRegion(name: String, variants: Int, tx: Int, ty: Int): RInfo? =
        if (variants > 0) atlas.region(name + (Mathf.randomSeed(packSeed(tx, ty), 0, variants - 1) + 1))
        else atlas.region(name)

    /** 画 region 到指定 tile 方格(放缩到输出分辨率, 中心对齐) */
    private fun blitTile(region: RInfo, tx: Int, ty: Int, tint: Int = -1, alpha: Int = 255, oxPx: Int = 0, oyPx: Int = 0, growPx: Int = 0) {
        atlas.pix(region) // 确保散图懒加载出尺寸
        val s = scale
        val dw = region.w * s / tsRender + growPx
        val dh = region.h * s / tsRender + growPx
        blitAt(region, (tx * s) + s / 2 - dw / 2 + oxPx, ((snap.h - 1 - ty) * s) + s / 2 - dh / 2 + oyPx, dw, dh, 0, tint, alpha)
    }

    /** 旋转版: 绕 tile 中心顺时针旋转 rotDeg(90 倍数), 用于建筑朝向 */
    private fun blitTileRot(region: RInfo, tx: Int, ty: Int, rotDeg: Int, tint: Int = -1, alpha: Int = 255, oxPx: Int = 0, oyPx: Int = 0) {
        if (rotDeg % 360 == 0) { blitTile(region, tx, ty, tint, alpha, oxPx, oyPx); return }
        atlas.pix(region)
        val s = scale
        val dw = region.w * s / tsRender
        val dh = region.h * s / tsRender
        val cx = (tx * s) + s / 2 + oxPx
        val cy = ((snap.h - 1 - ty) * s) + s / 2 + oyPx
        val q = ((rotDeg / 90) % 4 + 4) % 4
        // 旋转后(方形 region)尺寸不变
        blitAt(region, cx - dw / 2, cy - dh / 2, dw, dh, q, tint, alpha)
    }

    /**
     * 从图集 region 采样画到 out[dx,dy) 区域, 支持顺时针 q*90° 旋转与颜色 tint.
     * 像素格式: ABGR packed. alpha=0 跳过; 有 tint 或 alpha<255 时做 alpha 混合.
     */
    private fun blitAt(region: RInfo, dx: Int, dy: Int, dw: Int, dh: Int, q: Int, tint: Int, alpha: Int) {
        val pg = atlas.pix(region)
        for (tyy in 0 until dh) {
            val oy = dy + tyy
            if (oy < 0 || oy >= out.height) continue
            for (txx in 0 until dw) {
                val ox = dx + txx
                if (ox < 0 || ox >= out.width) continue
                val src = sample(region, pg, txx, tyy, dw, dh, q) ?: continue
                val a = chA(src)
                if (a == 0) continue
                val c = if (tint == -1) src else tintPx(src, chR(tint), chG(tint), chB(tint), alpha)
                if (c == src && alpha == 255) {
                    out.set(ox, oy, src)
                } else {
                    val srcA = chA(c)
                    if (srcA == 255) {
                        out.set(ox, oy, c)
                    } else {
                        val dst = out.get(ox, oy)
                        val da = 255 - srcA
                        out.set(ox, oy, rgbo(
                            (chR(c) * srcA + chR(dst) * da) / 255,
                            (chG(c) * srcA + chG(dst) * da) / 255,
                            (chB(c) * srcA + chB(dst) * da) / 255, 255))
                    }
                }
            }
        }
    }

    /** 目标(ox,oy) -> 源图集像素 (近邻, 支持 90 度旋转) */
    private fun sample(region: RInfo, page: Pixmap, txx: Int, tyy: Int, dw: Int, dh: Int, q: Int): Int? {
        val rw = region.w; val rh = region.h
        // 目标相对中心0.5偏移 -> 源坐标; 先求目标相对中心的浮点
        val pcx = (dw - 1) / 2f; val pcy = (dh - 1) / 2f
        val scx = (rw - 1) / 2f; val scy = (rh - 1) / 2f
        var fx = txx - pcx; var fy = tyy - pcy
        val fx0 = fx; val fy0 = fy
        when (q) {
            1 -> { fx = fy0; fy = -fx0 }           // 顺时针90
            2 -> { fx = -fx0; fy = -fy0 }          // 180
            3 -> { fx = -fy0; fy = fx0 }           // 逆时针90
        }
        val sx = (fx * rw / dw + scx).toInt().coerceIn(0, rw - 1)
        val sy = (fy * rh / dh + scy).toInt().coerceIn(0, rh - 1)
        return page.get(region.left + sx, region.top + sy)
    }

    private fun tintPx(src: Int, tr: Int, tg: Int, tb: Int, alpha: Int): Int {
        val a = if (alpha == 255) chA(src) else chA(src) * alpha / 255
        return rgbo(chR(src) * tr / 255, chG(src) * tg / 255, chB(src) * tb / 255, a)
    }

    // ---- 渲染总流程 ----
    fun render() {
        val w = snap.w; val h = snap.h
        // 1. 地面 + 边缘 + 覆盖物
        for (ty in 0 until h) {
            for (tx in 0 until w) renderFloor(tx, ty)
        }
        // 1.5 block edge outline：每个block底部+右侧画1px暗边(复刻游戏的block轮廓立体感)
        for (ty in 0 until snap.h) for (tx in 0 until snap.w) {
            val bid = snap.blocks[ty * snap.w + tx]
            if (bid == 0) continue
            val b = Vars.content.block(bid) ?: continue
            if (!b.hasShadow) continue
            val s = scale; val a = 0x28  // ~16%暗度
            val inv = 255 - a
            // 底边: 在当前block底部(y偏移1格)画1px深色
            val bx0 = tx * s; val by = (snap.h - 1 - ty) * s + s - 1
            if (by >= 0 && by < out.height) for (dx in 0 until s) {
                val px = bx0 + dx; if (px < 0 || px >= out.width) continue
                val d = out.get(px, by); out.set(px, by, rgbo(chR(d)*inv/255, chG(d)*inv/255, chB(d)*inv/255, 255))
            }
            // 右边: 在当前block右侧(x偏移1格)画1px深色
            val rx = tx * s + s - 1
            if (rx >= 0 && rx < out.width) for (dy in 0 until s) {
                val py = (snap.h - 1 - ty) * s + dy; if (py < 0 || py >= out.height) continue
                val d = out.get(rx, py); out.set(rx, py, rgbo(chR(d)*inv/255, chG(d)*inv/255, chB(d)*inv/255, 255))
            }
        }
        // 2. 静态墙 / 环境物件 / 悬崖
        for (ty in 0 until h) {
            for (tx in 0 until w) {
                val i = ty * w + tx
                val bid = snap.blocks[i]
                // 跳过: 空气 / 已有建筑的 origin tile / 多格建筑的填充 tile(fillsTile, 由 build 统一画)
                if (bid == 0 || snap.buildPos.contains(i) || blockFills(bid)) continue
                renderStatic(tx, ty, i, bid)
            }
        }
        // 3. 建筑 (已按远近排序)
        for (b in snap.builds) {
            renderBuild(b)
        }
        if (missingRegions.isNotEmpty()) {
            Log.warn("[renderMap] 缺失贴图 @ 个: @", missingRegions.size, missingRegions.take(60).joinToString(","))
            missingRegions.clear()
        }
    }

    // ---- 地面临近 tile 信息 ----
    private fun nearbyFloorInfo(tx: Int, ty: Int): Pair<Floor?, FloorInfo?> {
        val fid = snap.floors[ty * snap.w + tx]
        if (fid == 0) return null to null
        val f = Vars.content.block(fid) as? Floor ?: return null to null
        return f to floorInfo(fid)
    }

    // ---- 单个地面 tile ----
    private fun renderFloor(tx: Int, ty: Int) {
        val i = ty * snap.w + tx
        val fid = snap.floors[i]
        if (fid == 0) return
        val f = Vars.content.block(fid) as? Floor ?: return
        val info = floorInfo(fid) ?: return

        // ===== drawMain =====
        if (info.tilingVariants > 0) {
            val tsize = 3
            val idx = Mathf.randomSeed(packSeed(tx / tsize, ty / tsize), 0, maxOf(0, info.tilingVariants - 1))
            val reg = atlas.region(f.name + "-tile" + (idx + 1))
            if (reg != null) {
                val cell = reg.w / tsize
                // 客户端: regions[tile.x % tsize][tilingSize - 1 - tile.y % tsize], y 反向
                val sx = (tx % tsize) * cell
                val sy = ((tsize - 1 - ty % tsize) % tsize) * cell
                blitTile(RInfo(reg.name + "-tile", reg.page, reg.left + sx, reg.top + sy, cell, cell, reg.file), tx, ty)
            }
        } else if (info.autotile) {
            var bits = 0
            for (d in 0 until 8) {
                val p = Geometry.d8[d]
                val ox = tx + p.x; val oy = ty + p.y
                if (ox in 0 until snap.w && oy in 0 until snap.h) {
                    val of = snap.floors[oy * snap.w + ox]
                    if (of != 0) {
                        val ob = Vars.content.block(of) as? Floor
                        if (ob != null && ob.blendGroup == f.blendGroup) bits = bits or (1 shl d)
                    }
                }
            }
            val bit = TileBitmask.values[bits]
            val reg = if (bit == 13 && info.autotileMidVariants > 1) {
                val m = Mathf.randomSeed(packSeed(tx, ty), 0, info.autotileMidVariants - 1)
                if (m == 0) atlas.region(f.name + "-13")
                else atlas.region(f.name + "-mid-" + (m + 1))
            } else {
                atlas.region(f.name + "-" + bit)
            }
            reg?.let { blitTile(it, tx, ty) }
        } else {
            val reg = variantRegion(f.name, info.variants, tx, ty)
            if (reg != null) blitTile(reg, tx, ty)
        }

        // ===== drawEdges (边缘融合) =====
        if (info.drawEdgeIn) {
            renderEdges(tx, ty, i, f, info)
        }

        // ===== drawOverlay =====
        val oid = snap.overlays[i]
        if (oid != 0 && oid != fid) {
            val o = Vars.content.block(oid) as? Floor
            if (o != null) {
                renderOverlay(o, tx, ty)
                // 液体上叠覆盖物: 重画地面半透明 (水下混合)
                if (info.isLiquid && o !is OreBlock) {
                    val reg = variantRegion(f.name, info.variants, tx, ty)
                    if (reg != null) blitTile(reg, tx, ty, alpha = (255 * (1f - info.overlayAlpha)).toInt())
                }
            }
        }
    }

    /** 复刻 Floor.drawEdges: 8 邻域中比本 tile blend 优先级高的 floor 画其 edge mask */
    private fun renderEdges(tx: Int, ty: Int, i: Int, f: Floor, info: FloorInfo) {
        val floorIdx = ty * snap.w + tx
        // realBlendId of the tile being drawn
        val myBlend = realBlendId(f, info, floorIdx)

        // 收集: dir -> obs (ob.id), 以及唯一 ob
        val dirs = IntArray(8)
        val used = IntArray(8) { -1 }
        var count = 0
        for (d in 0 until 8) {
            val p = Geometry.d8[d]
            val ox = tx + p.x; val oy = ty + p.y
            if (ox !in 0 until snap.w || oy !in 0 until snap.h) continue
            val oi = oy * snap.w + ox
            val otherFloorId = snap.floors[oi]
            val otherOverlayId = snap.overlays[oi]
            val ob: Floor? = if (otherOverlayId == 0) {
                Vars.content.block(otherFloorId) as? Floor
            } else {
                // this == tile.floor() 恒真 -> ob = other.overlay
                Vars.content.block(otherOverlayId) as? Floor
            }
            if (ob == null) continue
            val oi2 = floorInfo(ob.id.toInt()) ?: continue
            if (!oi2.drawEdgeOut) continue
            // 邻 tile 的 cacheLayer 须与本地面一致 (水面与地面之间不融合)
            val otherF = Vars.content.block(otherFloorId) as? Floor ?: continue
            if (otherF.cacheLayer.id != info.cacheLayerOrd) continue
            // doEdge: 对方 realBlendId 更大, 或本地面没有 edge 图
            val otherBlend = realBlendId(ob, oi2, oi)
            val ownEdge = atlas.edgeBase(info.edgeGroup ?: "none", mainRegion(f)) != null
            val edgeOk = ownEdge
            if (otherBlend > myBlend || !edgeOk) {
                dirs[d] = ob.id.toInt()
                if (used.none { it == ob.id.toInt() }) {
                    val idx = count++; used[idx] = ob.id.toInt()
                }
            }
        }

        // 按 blendId 升序绘制(低优先先画)
        val finals = ArrayList<Pair<Int, Floor>>()
        for (k in 0 until count) {
            val ob = Vars.content.block(used[k]) as? Floor ?: continue
            val oinfo = floorInfo(ob.id.toInt()) ?: continue
            finals.add(oinfo.blendId to ob)
        }
        finals.sortBy { it.first }
        for ((_, ob) in finals) {
            for (d in 0 until 8) {
                if (dirs[d] != ob.id.toInt()) continue
                val p = Geometry.d8[d]
                val cell = edgeCell(ob, 1 - p.x, 1 - p.y) ?: continue
                blitTile(cell, tx, ty)
            }
        }
    }

    private fun realBlendId(f: Floor, info: FloorInfo, tileIdx: Int): Int {
        val tf = snap.floors[tileIdx]
        val to = snap.overlays[tileIdx]
        val of = Vars.content.block(tf) as? Floor
        val oo = if (to != 0) Vars.content.block(to) as? Floor else null
        if (of != null && of.isLiquid && oo != null && oo !is OreBlock) {
            return -((oo.blendId) or (of.blendId shl 15))
        }
        return info.blendId
    }

    /** floor 主线主图(用于 edge 生成): name 或 name1 */
    private fun mainRegion(f: Floor): RInfo? =
        atlas.region(f.name) ?: atlas.region(f.name + "1") ?: atlas.region(f.name + "2")

    /** edge mask 3x3 的某一格: 从 blendGroup 的 edge 图切 cell (grid[rx][2-ry]) */
    private fun edgeCell(ob: Floor, rx: Int, ry: Int): RInfo? {
        val group = ob.blendGroup.name
        val base = atlas.edgeBase(group, mainRegion(ob)) ?: return null
        val cell = base.w / 3
        val ci = rx.coerceIn(0, 2)
        val cj = (2 - ry).coerceIn(0, 2)
        return RInfo(base.name, base.page, base.left + ci * cell, base.top + cj * cell, cell, cell, base.file)
    }

    private fun renderOverlay(o: Floor, tx: Int, ty: Int) {
        val reg = variantRegion(o.name, o.variants, tx, ty)
        if (reg != null) blitTile(reg, tx, ty)
    }

    // ---- 块阴影(复刻 BlockRenderer.updateShadows + drawShadows 的半格投影) ----
    private fun renderShadows() {
        val w = snap.w; val h = snap.h
        for (ty in 0 until h) {
            for (tx in 0 until w) {
                val i = ty * w + tx
                val bid = snap.blocks[i]
                if (bid == 0) continue
                val b = Vars.content.block(bid) ?: continue
                if (!b.hasShadow) continue
                if (b.customShadow) {
                    val sh = atlas.region(b.name + "-shadow")
                    if (sh != null) blitTile(sh, tx, ty, tint = 0xff000000.toInt(), alpha = (0.71f * 255).toInt())
                } else {
                    // 普通块: 右下(世界 x+1, y-1 = 屏幕右下)投影到相邻格, 自身格不变暗(复刻游戏块投影)
                    fillShadow(tx + 1, ty - 1, (0.3f * 255).toInt())
                }
            }
        }
    }

    private fun fillShadow(tx: Int, ty: Int, alpha: Int) {
        val s = scale
        if (tx < 0 || ty < 0 || tx >= snap.w || ty >= snap.h) return
        val x0 = tx * s; val y0 = (snap.h - 1 - ty) * s
        val inv = 255 - alpha
        for (yy in y0 until y0 + s) {
            for (xx in x0 until x0 + s) {
                val d = out.get(xx, yy)
                out.set(xx, yy, rgbo(chR(d) * inv / 255, chG(d) * inv / 255, chB(d) * inv / 255, 255))
            }
        }
    }

    /** 给wall底部+右侧画1px暗边(立体感) */
    private fun drawWallEdge(tx: Int, ty: Int) {
        val s = scale
        val baseColor = 0x3a3530  // 深棕暗边色
        val alpha = 0x35  // ~21%暗度
        // bottom edge: 1px高, s像素宽, 在wall底部(y+1tile)
        val bx0 = tx * s
        val by = (snap.h - ty) * s  // 底边y坐标(世界y向下映射)
        for (dx in 0 until s) {
            val px = bx0 + dx; val py = by - 1
            if (px < 0 || px >= out.width || py < 0 || py >= out.height) continue
            val d = out.get(px, py)
            out.set(px, py, rgbo(
                chR(d) * (255 - alpha) / 255, chG(d) * (255 - alpha) / 255, chB(d) * (255 - alpha) / 255, 255))
        }
        // right edge: 1px宽, s像素高, 在wall右侧(x+1tile)
        val rx = (tx + 1) * s
        for (dy in 0 until s) {
            val px = rx; val py = (snap.h - 1 - ty) * s + dy
            if (px < 0 || px >= out.width || py < 0 || py >= out.height) continue
            val d = out.get(px, py)
            out.set(px, py, rgbo(
                chR(d) * (255 - alpha) / 255, chG(d) * (255 - alpha) / 255, chB(d) * (255 - alpha) / 255, 255))
        }
    }

    // ---- 静态物件 ----    // ---- 静态物件 ----
    private fun renderStatic(tx: Int, ty: Int, i: Int, bid: Int) {
        val b = Vars.content.block(bid) ?: return
        when (b) {
            is Cliff -> {
                val maskName = "cliffmask" + ((snap.datas[i] and 0xff) + 1)
                val reg = atlas.region(maskName) ?: return
                val fid = snap.floors[i]
                val fl = Vars.content.block(fid) as? Floor ?: return
                val c = fl.mapColor
                val t = rgbo(
                    (c.r * 1.6f * 255).toInt().coerceIn(0, 255),
                    (c.g * 1.6f * 255).toInt().coerceIn(0, 255),
                    (c.b * 1.6f * 255).toInt().coerceIn(0, 255), 255)
                blitTile(reg, tx, ty, tint = t, growPx = (0.04f * scale).toInt())
            }
            is StaticWall -> renderStaticWall(b, tx, ty, growPx = (0.04f * scale).toInt())
            else -> {
                val reg = variantRegion(b.name, b.variants, tx, ty) ?: atlas.region(b.name) ?: return
                blitTile(reg, tx, ty)
            }
        }
    }

    private fun renderStaticWall(b: StaticWall, tx: Int, ty: Int, growPx: Int = 0) {
        if (b.autotile) {
            var bits = 0
            for (d in 0 until 8) {
                val p = Geometry.d8[d]
                val ox = tx + p.x; val oy = ty + p.y
                if (ox in 0 until snap.w && oy in 0 until snap.h) {
                    if (Vars.content.block(snap.blocks[oy * snap.w + ox]) === b) bits = bits or (1 shl d)
                }
            }
            val bit = TileBitmask.values[bits]
            val reg = if (bit == 13 && b.autotileMidVariants > 1) {
                val m = Mathf.randomSeed(packSeed(tx, ty), 0, b.autotileMidVariants - 1)
                if (m == 0) atlas.region(b.name + "-13") else atlas.region(b.name + "-mid-" + (m + 1))
            } else {
                atlas.region(b.name + "-" + bit)
            }
            reg?.let { blitTile(it, tx, ty, growPx = growPx) }
        } else {
            val reg = variantRegion(b.name, b.variants, tx, ty) ?: atlas.region(b.name)
            if (reg == null) { missingRegions.add(b.name); return }
            blitTile(reg, tx, ty, growPx = growPx)
        }
        // 墙上矿物
        val oid = snap.overlays[ty * snap.w + tx]
        if (oid != 0) {
            val o = Vars.content.block(oid) as? Floor
            if (o != null && o.wallOre) renderOverlay(o, tx, ty)
        }
        // 底部+右侧1px暗边(复刻游戏的growSprites+shadow视觉效果)
        drawWallEdge(tx, ty)
    }

    // ---- 建筑 ----
    /** Autotiler blendbits + 贴图查找(针对conduit/duct/conveyor等) */
    private fun autotileRegion(b: BuildSnap, block: Block): RInfo? {
        val rot = b.rot
        fun blends(dir: Int): Boolean {
            val realDir = Math.floorMod(rot - dir, 4)
            val p = Geometry.d4[realDir]
            val nx = b.x + p.x; val ny = b.y + p.y
            if (nx < 0 || ny < 0 || nx >= snap.w || ny >= snap.h) return false
            val ni = ny * snap.w + nx
            val nbid = snap.blocks[ni]
            if (nbid == 0) return false
            val nb = snap.buildByTile[ni] ?: return false
            if (nb.team != b.team || Vars.content.block(nbid)?.name != block.name) return false
            return true
        }
        val b1 = blends(1); val b2 = blends(2); val b3 = blends(3)
        val num = when {
            b1 && b2 && b3 -> 0; b1 && b3 -> 1; b1 && b2 -> 2
            b3 && b2 -> 3; b1 -> 4; b3 -> 5; else -> -1
        }
        val blend = when(num){ 0->3; 1->1; 2->2; 3->2; 4->1; 5->1; else->-1 }
        if (blend < 0) return atlas.region(block.name)
        // 尝试多种贴图命名格式(按建筑类型):
        // 1) conduit类: block-top-{blend}
        // 2) conveyor类: block-{blend}-0
        // 3) duct类: block-top-{blend}
        // 4) 通用: block-{blend}
        for (fmt in listOf(block.name + "-top-$blend", block.name + "-$blend-0", block.name + "-$blend")) {
            val r = atlas.region(fmt)
            if (r != null) return r
        }
        return atlas.region(block.name)
    }

    private fun renderBuild(b: BuildSnap) {
        val block = Vars.content.block(b.blockId) ?: return
        val (variants, rotate) = blockMeta(b.blockId)
        val reg = if(block.name.startsWith("conveyor") || block.name.startsWith("duct") || block.name.startsWith("conduit")) {
            autotileRegion(b, block) ?: atlas.region(block.name)
        } else variantRegion(block.name, variants, b.x, b.y) ?: atlas.region(block.name)
        val rot = if (rotate) b.rot * 90 else 0
        // Block.offset 世界单位(8px/tile) 转像素偏移; 世界 y 向上, 像素 y 向下 -> oy 取负
        val oxPx = (block.offset * scale / 8f).toInt()
        val oyPx = -(block.offset * scale / 8f).toInt()
        if (reg != null) blitTileRot(reg, b.x, b.y, rot, oxPx = oxPx, oyPx = oyPx)
        // drawTeamTop
        val team = Team.all.getOrNull(b.team) ?: return
        val teamRegion = atlas.region(block.name + "-team-" + team.name)
        renderBuildParts(b, block, oxPx, oyPx, rot)
        if (teamRegion != null) {
            // 专属队色贴图(已上色), 直接画
            blitTileRot(teamRegion, b.x, b.y, rot, oxPx = oxPx, oyPx = oyPx)
        } else {
            // 灰白模板 x 队伍色(复刻客户端 Draw.color(team.color) + Draw.rect)
            val teamReg = atlas.region(block.name + "-team") ?: return
            val tc = team.color
            blitTileRot(teamReg, b.x, b.y, rot, oxPx = oxPx, oyPx = oyPx,
                tint = rgbo((tc.r * 255).toInt(), (tc.g * 255).toInt(), (tc.b * 255).toInt(), 255))
        }
    }

    // 部件层: 复刻游戏建筑多层组合渲染(base 之上叠加 liquid/top/cap/glow/rotator 等部件贴图)
    private fun renderBuildParts(b: BuildSnap, block: Block, oxPx: Int, oyPx: Int, rot: Int) {
        val parts = arrayOf(
            "liquid" to 0,
            "top" to 0,
            "cap" to 0,
            "glow" to 0,
            "cell" to 0,
            "rotator" to rot,
            "head" to rot
        )
        for ((suf, deg) in parts) {
            val r = atlas.region(block.name + "-" + suf) ?: continue
            blitTileRot(r, b.x, b.y, deg, oxPx = oxPx, oyPx = oyPx)
        }
    }
}

// ==================== 渲染调度 ====================
private fun renderMapNow(scale: Int): Fi? {
    val snap = takeSnap()
    Log.info("[renderMap] 建筑快照: @ 个, 地图 @x@", snap.builds.size, snap.w, snap.h)
    val bc = java.util.HashMap<Int, Int>()
    for (bid in snap.blocks) if (bid > 0) bc.merge(bid, 1) { a, b -> a + b }
    Log.info("[renderMap] 所有非空气block (id→name ×count):")
    bc.entries.sortedByDescending { it.value }.forEach { (bid, n) -> Log.info("[renderMap]   @ →@ ×@", bid, Vars.content.block(bid)?.name ?: "?", n) }
    val s = scale.coerceIn(1, 32)
    val w = snap.w * s; val h = snap.h * s
    if (w.toLong() * h * 4 > 1_200_000_000L) {
        Log.warn("[renderMap] 图片过大 @x@ (scale @), 请调小 scale", w, h, s)
        return null
    }
    atlas.parse(sourceDir)
    val out = Pixmap(w, h)
    out.fill(Color.valueOf("5a6872"))
    val r = Renderer(snap, s, out)
    r.render()
    val rawName = Strings.stripColors(Vars.state.map?.name() ?: "map")
    val name = rawName.filter { it.isLetterOrDigit() || it in "._-()" }
    val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    val file = outDir.child("$name-${s}px-${LocalDateTime.now().format(fmt)}.png")
    PixmapIO.writePng(file, out)
    Log.info("[renderMap] 已保存 @", file.file().absolutePath)
    return file
}


// ==================== 命令 ====================
command("renderMap", "{tr renderMap.command.desc}".with()) {
    aliases = listOf("全图截图")
    usage = "{tr renderMap.command.usage}"
    attr(NotForClient) // 仅终端: 玩家 help 不显示且不可调用
    body {
        val p = player
        if (p != null && !p.hasPermission("wayzer.admin.renderMap")) {
            returnReply("{tr renderMap.reply.noPermission}".with("receiver" to p))
        }
        // 本地消息辅助: 玩家带 receiver, 控制台不带
        fun replyTr(key: String, vararg extra: Pair<String, Any>) {
            val args = if (p != null) arrayOf("receiver" to p, *extra) else extra
            reply("{tr $key}".with(*args))
        }
        val scale = arg.firstOrNull()?.toIntOrNull() ?: 8
        replyTr("renderMap.reply.start", "scale" to scale)
        launch(Dispatchers.Default) {
            try {
                val file = renderMapNow(scale)
                launch(Dispatchers.game) {
                    if (file == null) {
                        replyTr("renderMap.reply.fail")
                    } else {
                        replyTr("renderMap.reply.done", "path" to file.file().absolutePath)
                    }
                }
            } catch (e: Throwable) {
                Log.err("[renderMap] 渲染失败", e)
                launch(Dispatchers.game) {
                    replyTr("renderMap.reply.error", "msg" to (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }
}
