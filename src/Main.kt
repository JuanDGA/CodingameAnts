import java.util.*
import java.util.stream.Collectors
import kotlin.math.floor
import kotlin.math.min

private operator fun <E> List<E>.component6(): E = this[5]
private operator fun <E> List<E>.component7(): E = this[6]
private operator fun <E> List<E>.component8(): E = this[7]

private fun debug(vararg element: Any) {
  System.err.println(element.joinToString(" | "))
}

fun main() {
  val numberOfCells = readln().toInt()

  val board = Board(numberOfCells)
  val game = Game(board, numberOfCells)

  val numberOfBases = readln().toInt()

  board.initBases(numberOfBases)

  val ownBases = readln().split(" ").map {it.toInt()}
  val oppBases = readln().split(" ").map {it.toInt()}

  repeat(numberOfBases) {
    board.addBase(it, ownBases[it])
    board.addOppBase(it, oppBases[it])
  }

  while (true) {
    board.newTurn(false)
    game.newTurn()
    game.update()
    board.newTurn(true)
    game.compute()
    game.doActions()
  }
}

class Cell(private val type: Int, var resources: Int, var ants: Int, var oppAnts: Int, var position: Int) {
  val neighbors = arrayListOf<Int>()
  fun hasEgg() = this.type == 1 && !isEmpty()
  fun hasCrystal() = this.type == 2 && !isEmpty()
  fun isEmpty() = this.resources == 0

  fun addNeighbor(index: Int) {
    if (index == -1) return
    neighbors.add(index)
  }

  fun getScore(board: Board, usedCells: Set<Int>): Int {
    val base = board.getBases()
      .filter { board.getDistance(it, position) != -1 }
      .minBy { board.getDistance(it, position) }

    val strength = floor(board.countAnts().toDouble() / board.getDistance(base, position) + 1)
    return min(resources.toDouble(), strength).toInt()
  }
}

class Bfs(numberOfCells: Int) {
  private val pathsCache = Array(numberOfCells) {
    Array<Pair<List<Int>?, Boolean>>(numberOfCells) {
      null to false
    }
  }

  fun findShortestPath(from: Cell, to: Cell, board: Board, ants: Boolean = false): List<Int>? {
    val exists = pathsCache[from.position][to.position]

    if (exists.second) return exists.first

    val queue = LinkedList<Cell>()
    val prev = mutableMapOf<Int, Int?>()

    prev[from.position] = null
    queue.add(from)

    while (queue.isNotEmpty()) {
      if (prev.containsKey(to.position)) break

      val head = queue.pop()

      val neighbors = if (ants) head.neighbors.filter { board.getCell(it).ants > 0 } else head.neighbors.sorted()

      for (neigh in neighbors) {
        val neighVisited = prev.containsKey(neigh)
        if (!neighVisited) {
          prev[neigh] = head.position
          queue.add(board.getCell(neigh))
        }
      }
    }

    if (!prev.containsKey(to.position)) {
      pathsCache[from.position][to.position] = null to true
      return null
    }

    val path = LinkedList<Int>()
    var current: Int? = to.position

    while (current != null) {
      path.addFirst(current)
      current = prev[current]
    }

    pathsCache[from.position][to.position] = path to true

    return path
  }

  fun findAttackPath(base: Int, target: Int, board: Board): LinkedList<Int>? {
    val maxPathValues = IntArray(board.numberOfCells) {Int.MIN_VALUE}
    val prev = IntArray(board.numberOfCells) {-1}
    val distanceFromStart = IntArray(board.numberOfCells)
    val visited = BooleanArray(board.numberOfCells) {false}

    val valueComparator = Comparator.comparing { cellIndex: Int -> maxPathValues[cellIndex] }
    val distanceComparator = Comparator.comparing { cellIndex: Int -> distanceFromStart[cellIndex] + board.getDistance(cellIndex, target) }

    val queue: PriorityQueue<Int> = PriorityQueue(valueComparator.reversed().thenComparing(distanceComparator))

    maxPathValues[base] = board.getCell(base).oppAnts
    distanceFromStart[base] = 0

    val startAnts = board.getCell(base).oppAnts

    if (startAnts > 0) queue.add(base)

    while (queue.isNotEmpty() && !visited[target]) {
      val current = queue.poll()
      visited[current] = true

      for (neigh in board.getCell(current).neighbors) {
        val neighCell = board.getCell(neigh)
        val neighAnts = neighCell.oppAnts

        if (!visited[neigh] && neighAnts > 0) {
          val potentialMaxPathValue = min(maxPathValues[current], neighAnts)
          if (potentialMaxPathValue > maxPathValues[neigh]) {
            maxPathValues[neigh] = potentialMaxPathValue
            distanceFromStart[neigh] = distanceFromStart[current] + 1
            prev[neigh] = current
            queue.add(neigh)
          }
        }
      }
    }

    if (!visited[target]) {
      return null
    }

    val path = LinkedList<Int>()
    var currentIndex = target

    while (currentIndex != -1) {
      path.addFirst(currentIndex)
      currentIndex = prev[currentIndex]
    }

    return path
  }
}

data class BoardCache(
  var crystalCells: List<Cell> = emptyList(),
  var eggCells: List<Cell> = emptyList(),
)

class Board(val numberOfCells: Int) {
  private val distanceCache = Array(numberOfCells) { IntArray(numberOfCells) }
  private val bfs = Bfs(numberOfCells)

  private var upToDateCells = false
  private var updatingCells = true
  private var firstUpdate = true

  var initialEggs = 0
  var initialCrystal = 0

  private val cache = BoardCache()

  private lateinit var bases: IntArray
  private lateinit var oppBases: IntArray

  private var initialCellsWithResources = 0
  private var cellsWithResourcesIndices: IntArray
  private var cells: Array<Cell>

  init {
    val cellsIndices = arrayListOf<Int>()
    cells = Array(numberOfCells) {
      val (type, initialResources, neigh0, neigh1, neigh2, neigh3, neigh4, neigh5) = readln().split(" ")
        .map { p -> p.toInt() }
      val cell = Cell(type, initialResources, 0, 0, it)
      cell.addNeighbor(neigh0)
      cell.addNeighbor(neigh1)
      cell.addNeighbor(neigh2)
      cell.addNeighbor(neigh3)
      cell.addNeighbor(neigh4)
      cell.addNeighbor(neigh5)

      if (type != 0) {
        initialCellsWithResources += 1
        cellsIndices.add(it)
      }

      cell
    }

    cellsWithResourcesIndices = cellsIndices.toIntArray()
  }

  fun initBases(amount: Int) {
    this.bases = IntArray(amount) {-1}
    this.oppBases = IntArray(amount) {-1}
  }

  fun addBase(base: Int, cell: Int) {
    this.bases[base] = cell
  }


  fun addOppBase(base: Int, cell: Int) {
    this.oppBases[base] = cell
  }

  fun updateCell(index: Int, resources: Int, ants: Int, oppAnts: Int) {
    cells[index].resources = resources
    cells[index].ants = ants
    cells[index].oppAnts = oppAnts
  }

  fun getBases() = this.bases
  fun getOppBases() = this.oppBases

  fun getCrystalCells(): List<Cell> {
    if (upToDateCells) return cache.crystalCells

    val crystalCells = cells.filter { it.hasCrystal() }.map { it }

    cache.crystalCells = crystalCells
    return crystalCells
  }

  fun getEggsCells(): List<Cell> {
    if (upToDateCells) return cache.eggCells

    val eggCells = cells.filter { it.hasEgg() }.map { it }

    cache.eggCells = eggCells
    return eggCells
  }

  fun getCell(index: Int) = cells[index]

  fun newTurn(upToDateCells: Boolean) {
    this.upToDateCells = upToDateCells
    this.updatingCells = !upToDateCells
    if (firstUpdate) {
      firstUpdate = false
      val (eggs, crystal) = getResourcesInfo()
      initialEggs = eggs.toInt()
      initialCrystal = crystal.toInt()
    }
  }

  private fun upToDate() = this.upToDateCells && !this.updatingCells

  fun resourcesIndices() = this.cellsWithResourcesIndices

  fun getPrioritization(): BooleanArray {
    val (currentEggs, currentCrystal) = getResourcesInfo()
    val eggs = currentEggs / initialEggs
    val crystal = currentCrystal / initialCrystal

    val goForEggs = eggs > 0.7
    val goForCrystal = crystal < 0.7 || getCrystalCells().size == 1

    val prioritizeEggs = goForEggs && !goForCrystal
    val prioritizeCrystal = goForCrystal && !goForEggs
    val noPrioritization = (goForCrystal && goForEggs) || (!goForCrystal && !goForEggs)

    return booleanArrayOf(prioritizeEggs, prioritizeCrystal, noPrioritization)
  }

  private fun getResourcesInfo(): Pair<Double, Double> {
    var totalEggs = 0.0
    var totalCrystal = 0.0

    resourcesIndices().forEach {
      val cell = cells[it]
      if (cell.hasEgg()) {
        totalEggs += cell.resources
      } else if (cell.hasCrystal()) {
        totalCrystal += cell.resources
      }
    }

    return totalEggs to totalCrystal
  }

  fun getAttackPower(target: Int): Int {
    return 0
  }

  fun getOpponentAttackPower(target: Int): Int {
    val bases = getOppBases()

    val allPaths = arrayListOf<LinkedList<Int>>()

    for (base in bases) {
      val bestPath = getAttackPath(base, target)

      if (bestPath != null) {
        allPaths.add(bestPath)
      }
    }

    return allPaths.stream()
      .mapToInt {
        it.stream()
          .mapToInt { c -> getCell(c).oppAnts }
          .min()
          .orElse(0)
      }
      .max()
      .orElse(0)
  }

  private fun getAttackPath(base: Int, target: Int): LinkedList<Int>? {
    return bfs.findAttackPath(base, target, this)
  }

  fun getBestPath(from: Cell, to: Cell, usingCells: Set<Int>): List<Int>? {
    if (usingCells.isEmpty()) return bfs.findShortestPath(from, to, this)

    val minConnectedByDistance = usingCells
      .filter { getDistance(it, to.position) != -1 }
      .minBy { getDistance(it, to.position) }

    val originalDistance = getDistance(from.position, to.position)
    val minConnectedDistance = getDistance(minConnectedByDistance, to.position)

    return if (originalDistance > minConnectedDistance) {
      bfs.findShortestPath(getCell(minConnectedByDistance), to, this)
    } else bfs.findShortestPath(from, to, this)
  }

  fun getDistance(from: Int, to: Int): Int {
    if (from == to) return 0

    val cached = distanceCache[from][to]
    if (cached > 0) return cached

    val distance = calcDist(from, to)

    distanceCache[from][to] = distance
    distanceCache[to][from] = distance

    return distance
  }

  private fun calcDist(from: Int, to: Int): Int {
    val path = bfs.findShortestPath(getCell(from), getCell(to), this) ?: return -1

    return path.size - 1
  }

  fun countAnts(): Int {
    return cells.sumOf { it.ants }
  }
}

class CellComparator(private val board: Board, private val usedCells: Set<Int>) : Comparator<Int> {
  override fun compare(cell1: Int, cell2: Int): Int {
    val (prioritizeEggs, prioritizeCrystal, noPrioritization) = board.getPrioritization()

    val o1 = board.getCell(cell1)
    val o2 = board.getCell(cell2)

    if (o1.isEmpty() && !o2.isEmpty()) return 1
    if (!o1.isEmpty() && o2.isEmpty()) return -1
    if (o1.isEmpty() && o2.isEmpty()) return 0

    val sC = o2.getScore(board, usedCells).compareTo(o1.getScore(board, usedCells))

    if (prioritizeEggs) {
      return o2.getScore(board, usedCells).compareTo(o1.getScore(board, usedCells))
    } else if (prioritizeCrystal) {
      return o1.getScore(board, usedCells).compareTo(o2.getScore(board, usedCells))
//      return if (sC == 0) {
//        val baseA = board.getBases()
//          .filter { board.getDistance(it, cell1) != -1 }
//          .minBy { board.getDistance(it, cell1)}
//
//        val baseB = board.getBases()
//          .filter { board.getDistance(it, cell2) != -1 }
//          .minBy { board.getDistance(it, cell2)}
//
//        board.getDistance(baseB, cell2).compareTo(board.getDistance(baseA, cell1))
//      } else sC
    } else {
      return if (o1.hasEgg() && o2.hasCrystal()) -1
      else if (o1.hasCrystal() && o2.hasEgg()) 1
      else if (o1.hasEgg() && o2.hasEgg()) o2.getScore(board, usedCells).compareTo(o1.getScore(board, usedCells))
      else o1.getScore(board, usedCells).compareTo(o2.getScore(board, usedCells))
    }
  }
}

class Game(private val board: Board, private val numberOfCells: Int) {
  private val actions = arrayListOf<String>()
  private val messages = arrayListOf<String>()
  private val targets = mutableMapOf<Int, Int>()

  private val fixedTargets = mutableSetOf<Int>()

  fun update() {
    val (_, _) = readln().split(" ").map { it.toInt() }
    repeat(numberOfCells) {
      val (resources, ants, oppAnts) = readln().split(" ").map{ v -> v.toInt() }
      board.updateCell(it, resources, ants, oppAnts)
    }
  }

  fun newTurn() {
    targets.clear()
    actions.clear()
    messages.clear()
  }

  private fun sortedCells(usedCells: Set<Int>): List<Int> {
    val (prioritizeEggs, prioritizeCrystal, _) = board.getPrioritization()

    return board.resourcesIndices()
      .filter { if (prioritizeEggs) board.getCell(it).hasEgg() else if (prioritizeCrystal) board.getCell(it).hasCrystal() else true}
      .filter { !board.getCell(it).isEmpty() }
      .filter {
        val oppBase = board.getOppBases()
          .filter { b -> board.getDistance(b, it) != -1}
          .minBy { b -> board.getDistance(b, it) }

        !(board.getDistance(oppBase, it) == 1 && board.getCell(it).hasCrystal()) && board.getCell(it).resources > 0
      }
      .sortedWith(CellComparator(board, usedCells))
  }

  private fun validateFixedTargets(): MutableSet<Int> {
    fixedTargets.removeIf { board.getCell(it).resources == 0 }
    val newTargets = mutableSetOf<Int>()

    val currentCells = mutableSetOf<Int>()

    for (target in fixedTargets.sortedBy { board.getCell(it).oppAnts }) {
      val cell = board.getCell(target)

      val base = board.getBases()
        .filter { board.getDistance(it, cell.position) != -1 }
        .minBy { board.getDistance(it, cell.position) }

      val pathTo = board.getBestPath(board.getCell(base), board.getCell(cell.position), currentCells)!!
      val combined = mutableSetOf<Int>()
      combined.addAll(currentCells)
      combined.addAll(pathTo)

      val isSufficient = pathTo.all { board.getOpponentAttackPower(it) < floor(board.countAnts().toDouble() / combined.size.toDouble())}

      if (isSufficient) {
        currentCells.addAll(pathTo)
        newTargets.add(target)
      }
    }

    fixedTargets.clear()
    fixedTargets.addAll(newTargets)

    for (target in currentCells) {
      addBeacon(target, 1)
    }

    return currentCells
  }

  fun compute() {
    val usedCells = validateFixedTargets()
    val targets = sortedCells(usedCells).toMutableList()

    debug("Sorted targets: $targets")

    val (prioritizeEggs, prioritizeCrystal, _) = board.getPrioritization()

    when {
      prioritizeEggs -> debug("E")
      prioritizeCrystal -> debug("C")
      else -> debug("B")
    }

    val crystalsNextTo = targets.count {
      val c = board.getCell(it)

      val base = board.getBases()
        .filter { b -> board.getDistance(b, c.position) != -1 }
        .minBy { b -> board.getDistance(b, c.position) }

      c.hasCrystal() && board.getDistance(base, it) == 1
    }

    debug("Crystals next to: $crystalsNextTo")

    var approvedTargets = 0

    for (target in targets) {
      debug("Doing for $target")

      val cell = board.getCell(target)
      if (prioritizeEggs && !cell.hasEgg()) continue
      if (prioritizeCrystal && !cell.hasCrystal()) continue
      if (cell.getScore(board, usedCells) == 0) continue

      debug("First filter for $target")

      val base = board.getBases()
        .filter { board.getDistance(it, cell.position) != -1 }
        .minBy { board.getDistance(it, cell.position) }

      if (
        (approvedTargets - crystalsNextTo) > 0 &&
        board.getDistance(base, cell.position) == 1 &&
        cell.hasCrystal()
      ) continue

      debug("Second filter for $target")

      if (board.getDistance(base, cell.position) != 1) {
        if (fixedTargets.isNotEmpty()) {
          val elements = fixedTargets.stream().filter {f ->
            val baseForElem = board.getBases()
              .filter { board.getDistance(it, f) != -1 }
              .minBy { board.getDistance(it, f) }

            baseForElem == base
          }.collect(Collectors.toList())

          if (elements.isNotEmpty()) {
            if (elements.any { f ->
                val baseForElem = board.getBases()
                  .filter { board.getDistance(it, f) != -1 }
                  .minBy { board.getDistance(it, f) }

                board.getDistance(baseForElem, f) == 1
                    && board.getCell(f).hasEgg()
              }) continue
          }
        }
      }

      debug("Third filter for $target")

      val resultingCells = mutableSetOf<Int>()
      resultingCells.addAll(usedCells)

      val pathTo = board.getBestPath(board.getCell(base), board.getCell(cell.position), usedCells)!!

      resultingCells.addAll(pathTo)

      val antsPerCell = floor(board.countAnts().toDouble() / resultingCells.size)

      val isSufficient = pathTo.all { board.getOpponentAttackPower(it) <= antsPerCell }

      if (!isSufficient) continue

      debug("Fourth filter for $target")

      val sameBase = if (board.getBases().size == 1 || fixedTargets.isEmpty()) true
      else {
        val firstBase = board.getBases()
          .minBy { board.getDistance(it, fixedTargets.first()) }

        fixedTargets.all { board.getBases()
          .minBy { b -> board.getDistance(b, it) } == firstBase }
      }

      debug("Same base for $target: $sameBase")

      val minAnts = if (fixedTargets.isEmpty()) 1
      else if (board.getBases().size == 1) 3
      else if (sameBase)  {
        val firstBase = board.getBases()
          .minBy { board.getDistance(it, fixedTargets.first()) }

        if (base == firstBase) 3
        else 1
      }
      else 3

      debug("Required ants for $target: $minAnts. Ants per cell: $antsPerCell")

      if (antsPerCell < minAnts) continue

      debug("Fifth filter for $target")

      approvedTargets += 1
      fixedTargets.add(cell.position)
      usedCells.addAll(pathTo)
      createLine(pathTo)
    }
  }

  private fun addBeacon(index: Int, strength: Int) {
    if (targets.containsKey(index) && targets[index]!! < strength) targets[index] = strength
    else if (!targets.containsKey(index)) targets[index] = strength
  }

  private fun createLine( path: List<Int>, strength: Int = 1) {
    debug("Doing path: $path")
    val prioritizeEnd = path
    path.forEachIndexed { index, it ->
      addBeacon(it, if (index == path.size - 1 && board.getCell(it).ants == 0) strength * 2 else strength)
    }
  }

  private fun doWait() {
    actions.add("WAIT")
  }

  private fun doMessage(vararg items: Any) {
    messages.add(items.joinToString(" - "))
  }

  fun doActions() {
    targets.forEach { (k, v) -> actions.add("BEACON $k $v") }

    if (messages.isNotEmpty()) actions.add("MESSAGE ${messages.joinToString(" | ")}")
    if (actions.isEmpty()) doWait()
    println(actions.joinToString(";"))
  }
}
