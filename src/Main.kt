import java.util.*
import java.util.stream.Collectors
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.floor
import kotlin.math.min

const val DEBUGGING = false

const val MAX_GAME_TURNS = 100

const val DIRECTIONS = 6

private fun debug(vararg element: Any) {
  System.err.println(element.joinToString(" | "))
}

fun main() {
  val input = Scanner(System.`in`)

  val game = Game(input)

  repeat (MAX_GAME_TURNS) {
    game.newTurn()
    val antsNetwork = game.compute()
    game.defineMovement(antsNetwork)
  }
}

data class Cell(
  var id: Int,
  private val type: Int,
  var resources: Int = 0,
  var ownAnts: Int = 0,
  var opponentAnts: Int = 0,
  val neighbors: List<Int> = emptyList()
) {
  fun containsEggs() = this.type == 1 && !isEmpty()
  fun containsCrystal() = this.type == 2 && !isEmpty()
  fun isEmpty() = this.resources == 0

  companion object {
    fun from(id: Int, input: Scanner): Cell {
      return Cell(
        id,
        type = input.nextInt(),
        resources = input.nextInt(),
        neighbors = IntArray(DIRECTIONS) { input.nextInt() }.filter { it != -1 }
      )
    }
  }
}

class ExplorationCache(numberOfCells: Int) {
  private val distanceCache = Array(numberOfCells) { IntArray(numberOfCells) { -1 } }
  private val pathsCache = Array(numberOfCells) { Array(numberOfCells) { emptyList<Int>() } }

  fun getDistance(from: Int, to: Int): Int? {
    if (distanceCache[from][to] == -1) return null
    return distanceCache[from][to]
  }

  fun registerDistance(from: Int, to: Int, distance: Int) {
    distanceCache[from][to] = distance
    distanceCache[to][from] = distance
  }

  fun getPath(from: Int, to: Int): List<Int>? {
    if (pathsCache[from][to].isEmpty()) return null
    return pathsCache[from][to]
  }

  fun registerPath(from: Int, to: Int, path: List<Int>) {
    pathsCache[from][to] = path
    pathsCache[to][from] = path.reversed()
  }
}

class Game(private val input: Scanner) {
  private val numberOfCells = input.nextInt()
  private val explorationCache = ExplorationCache(numberOfCells)

  private val cells = (0 until numberOfCells).associateWith { Cell.from(it, input) }

  private val numberOfBases = input.nextInt()
  private val friendlyBases = Array(numberOfBases) { getCell(input.nextInt()) }
  private val opponentBases = Array(numberOfBases) { getCell(input.nextInt()) }

  private val initialCrystal = cells.values.sumOf { cell -> if (cell.containsCrystal()) cell.resources else 0 }
  private val initialEggs = cells.values.sumOf { cell -> if (cell.containsEggs()) cell.resources else 0 }

  private var currentCrystal = 0
  private var currentEggs = 0

  private var ownScore = 0
  private var opponentScore = 0

  private val actions = arrayListOf<String>()
  private val messages = arrayListOf<String>()

  private val targets = mutableMapOf<Int, Int>()

  private val fixedTargets = mutableSetOf<Cell>()

  fun newTurn() {
    updateProperties()
    readScores()
    readCells()
  }

  private fun updateProperties() {
    currentEggs = 0
    currentCrystal = 0
    targets.clear()
    actions.clear()
    messages.clear()
  }

  fun compute(): Set<Int> {
    debug(initialEggs, initialCrystal, currentEggs, currentCrystal)
    val usedCells = validateFixedTargets()
    val targets = getTargets()

    if (DEBUGGING) debug("Targets: ${targets.map { it.id }}")

    val (prioritizeEggs, prioritizeCrystal, none) = getPrioritization()

    if (DEBUGGING) debug("Current prioritization (E|C|N): ($prioritizeEggs|$prioritizeCrystal|$none)")

    val crystalsNextTo = targets.count { cell ->
      val base = getBase(cell)

      cell.containsCrystal() && getDistance(base, cell) == 1
    }

    if (DEBUGGING) debug("Crystals next to: $crystalsNextTo")

    var approvedTargets = 0

    for (cell in targets) {
      if (DEBUGGING) debug("Doing for ${cell.id}")
      if (prioritizeEggs && !cell.containsEggs()) continue
      if (prioritizeCrystal && !cell.containsCrystal()) continue
      if (getScore(cell) == 0) continue

      if (DEBUGGING) debug("First filter for ${cell.id}")

      val base = getBase(cell)

      if ((approvedTargets - crystalsNextTo) > 0 && getDistance(base, cell) == 1 && cell.containsCrystal()) continue

      if (DEBUGGING) debug("Second filter for ${cell.id}")

      if (getDistance(base, cell) != 1) {
        if (fixedTargets.isNotEmpty()) {
          val elements = fixedTargets.stream().filter { f ->
            val baseForElem = getBase(f)

            baseForElem == base
          }.collect(Collectors.toList())

          if (elements.isNotEmpty()) {
            if (elements.any { f ->
              val baseForElem = getBase(f)
              getDistance(baseForElem, f) == 1 && f.containsEggs()
            }) continue
          }
        }
      }

      if (DEBUGGING) debug("Third filter for ${cell.id}")

      val resultingCells = mutableSetOf<Int>()
      resultingCells.addAll(usedCells)

      val pathTo = getBestPath(base, cell, usedCells)

      resultingCells.addAll(pathTo)

      val antsPerCell = floor(getAnts().toDouble() / resultingCells.size)

      val isSufficient = pathTo.all { getOpponentAttackPower(getCell(it)) <= antsPerCell }

      if (!isSufficient) continue

      if (DEBUGGING) debug("Fourth filter for ${cell.id}")

      val sameBase = if (friendlyBases.size == 1 || fixedTargets.isEmpty()) true
      else {
        val firstBase = friendlyBases
          .minBy { getDistance(it, fixedTargets.first()) }

        fixedTargets.all { friendlyBases.minBy { b -> getDistance(b, it) } == firstBase }
      }

      if (DEBUGGING) debug("Same base for ${cell.id}: $sameBase")

      val minAnts = if (fixedTargets.isEmpty()) 1
      else if (friendlyBases.size == 1) 3
      else if (sameBase)  {
        val firstBase = friendlyBases.minBy { getDistance(it, fixedTargets.first()) }

        if (base == firstBase) 3 else 1
      }
      else 3

      if (DEBUGGING) debug("Required ants for ${cell.id}: $minAnts. Ants per cell: $antsPerCell")

      if (antsPerCell < minAnts) continue

      if (DEBUGGING) debug("Fifth filter for ${cell.id}")

      approvedTargets += 1
      fixedTargets.add(cell)
      usedCells.addAll(pathTo)
    }
    return usedCells
  }
  private fun getPrioritization(): BooleanArray {
    val eggs = currentEggs.toDouble() / initialEggs
    val crystal = currentCrystal.toDouble() / initialCrystal

    val goForEggs = eggs > 0.7
    val goForCrystal = crystal < 0.7 || cells.values.count { it.containsCrystal() } == 1

    val prioritizeEggs = goForEggs && !goForCrystal
    val prioritizeCrystal = goForCrystal && !goForEggs
    val noPrioritization = (goForCrystal && goForEggs) || (!goForCrystal && !goForEggs)

    return booleanArrayOf(prioritizeEggs, prioritizeCrystal, noPrioritization)
  }

  private fun getTargets(): List<Cell> {
    val (prioritizeEggs, prioritizeCrystal, prioritizeAny) = getPrioritization()

    return cells.values
      .filter { (prioritizeEggs && it.containsEggs()) || (prioritizeCrystal && it.containsCrystal()) || prioritizeAny }
      .filter {
        val oppBase = getOpponentBase(it)

        !(getDistance(oppBase, it) == 1 && it.containsCrystal()) && it.resources > 0
      }
      .sortedWith { cell1, cell2 -> compareCells(cell1, cell2) }
  }

  private fun getCell(id: Int): Cell {
    return cells[id] ?: throw Exception("Not found cell with id {$id}")
  }

  private fun getBase(cell: Cell) = friendlyBases.minBy { getDistance(it, cell) }
  private fun getOpponentBase(cell: Cell) = opponentBases.minBy { getDistance(it, cell) }

  private fun getScore(cell: Cell): Int {
    val base = getBase(cell)

    val strength = floor(getAnts().toDouble() / getDistance(base, cell)).toInt()
    return minOf(cell.resources, strength)
  }

  private fun getAnts() = cells.values.sumOf { it.ownAnts }

  private fun getDistance(from: Cell, to: Cell): Int {
    if (from == to) return 0

    var distance = explorationCache.getDistance(from.id, to.id)

    if (distance != null) return distance

    distance = calculateDistance(from, to)

    explorationCache.registerDistance(from.id, to.id, distance)

    return distance
  }

  private fun calculateDistance(from: Cell, to: Cell): Int {
    val visited = HashSet<Int>(numberOfCells)
    val queue: Queue<Pair<Cell, Int>> = LinkedList()

    queue.add(from to 0)
    visited.add(from.id)

    while (queue.isNotEmpty()) {
      val (head, distance) = queue.poll()

      if (head.id == to.id) return distance

      head.neighbors
        .filter { it !in visited }
        .forEach {
          visited.add(it)
          queue.add(getCell(it) to distance + 1)
        }
    }

    return -1
  }

  private fun getDistanceToAnts(from: Cell): Int {
    val visited = HashSet<Int>(numberOfCells)
    val queue: Queue<Pair<Cell, Int>> = LinkedList()

    queue.add(from to 0)
    visited.add(from.id)

    while (queue.isNotEmpty()) {
      val (head, distance) = queue.poll()

      if (head.ownAnts > 0) return distance

      head.neighbors
        .filter { it !in visited }
        .forEach {
          visited.add(it)
          queue.add(getCell(it) to distance + 1)
        }
    }

    return -1
  }

  private fun getBestPath(from: Cell, target: Cell, usedCells: Set<Int>): List<Int> {
    if (usedCells.isEmpty()) return getPath(from, target)
    val visited = HashSet<Int>(numberOfCells)
    val queue: Queue<Pair<Cell, List<Int>>> = LinkedList()

    queue.add(target to listOf(target.id))

    while (queue.isNotEmpty()) {
      val (head, path) = queue.poll()

      if (head.id in usedCells) return path

      head.neighbors
        .filter { it !in visited }
        .forEach {
          val cell = getCell(it)
          visited.add(cell.id)
          queue.add(cell to path + cell.id)
        }
    }

    return emptyList()
  }

  private fun getPath(from: Cell, to: Cell): List<Int> {
    var path = explorationCache.getPath(from.id, to.id)

    if (path != null) return path

    path = findPath(from, to)

    explorationCache.registerPath(from.id, to.id, path)

    return path
  }

  private fun findPath(from: Cell, to: Cell): List<Int> {
    val visited = HashSet<Int>(numberOfCells)
    val queue: Queue<Pair<Cell, List<Int>>> = LinkedList()

    queue.add(from to listOf(from.id))

    while (queue.isNotEmpty()) {
      val (head, path) = queue.poll()

      if (head.id == to.id) return path

      head.neighbors
        .filter { it !in visited }
        .forEach {
          val cell = getCell(it)
          visited.add(cell.id)
          queue.add(cell to path + cell.id)
        }
    }

    return emptyList()
  }

  private fun getOpponentAttackPower(target: Cell): Int {
    val allPaths = arrayListOf<List<Int>>()

    allPaths.addAll(opponentBases.map { getAttackPath(it, target) }.filter { it.isNotEmpty() })

    return allPaths.maxOfOrNull { it.minOfOrNull { cellId -> getCell(cellId).opponentAnts } ?: 0 } ?: 0
  }

  private fun getAttackPath(base: Cell, target: Cell): List<Int> {
    return getOpponentAttackPath(base, target)
  }

  private fun getOpponentAttackPath(base: Cell, target: Cell): List<Int> {
    val maxPathValues = IntArray(numberOfCells) { Int.MIN_VALUE }
    val prev = IntArray(numberOfCells) { -1 }
    val distanceFromStart = IntArray(numberOfCells)
    val visited = BooleanArray(numberOfCells)

    val valueComparator = compareByDescending<Int> { maxPathValues[it] }
    val distanceComparator = compareBy<Int> { distanceFromStart[it] + getDistance(getCell(it), target) }

    val queue: PriorityQueue<Int> = PriorityQueue(valueComparator.thenComparing(distanceComparator))

    maxPathValues[base.id] = base.opponentAnts
    distanceFromStart[base.id] = 0

    val startAnts = base.opponentAnts

    if (startAnts > 0) queue.add(base.id)

    while (queue.isNotEmpty() && !visited[target.id]) {
      val current = queue.poll()
      visited[current] = true

      for (neigh in getCell(current).neighbors) {
        val neighCell = getCell(neigh)
        val neighAnts = neighCell.opponentAnts

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

    if (!visited[target.id]) {
      return emptyList()
    }

    val path = LinkedList<Int>()
    var currentIndex = target.id

    while (currentIndex != -1) {
      path.addFirst(currentIndex)
      currentIndex = prev[currentIndex]
    }

    return path
  }

  private fun compareCells(cell1: Cell, cell2: Cell): Int {
    val (prioritizeEggs, prioritizeCrystal, _) = getPrioritization()

    if (cell1.isEmpty() && !cell2.isEmpty()) return 1
    if (!cell1.isEmpty() && cell2.isEmpty()) return -1
    if (cell1.isEmpty() && cell2.isEmpty()) return 0

    val scoreCell1 = getScore(cell1)
    val scoreCell2 = getScore(cell2)

    return if (prioritizeEggs) {
      scoreCell2.compareTo(scoreCell1)
    } else if (prioritizeCrystal) {
      scoreCell1.compareTo(scoreCell2)
    } else {
      if (cell1.containsEggs() && cell2.containsCrystal()) -1
      else if (cell1.containsCrystal() && cell2.containsEggs()) 1
      else if (cell1.containsEggs() && cell2.containsEggs()) scoreCell2.compareTo(scoreCell1)
      else scoreCell1.compareTo(scoreCell2)
    }
  }

  private fun validateFixedTargets(): MutableSet<Int> {
    fixedTargets.removeIf { it.resources == 0 }
    val newTargets = mutableSetOf<Cell>()

    val currentCells = mutableSetOf<Int>()

    for (cell in fixedTargets.sortedBy { it.opponentAnts }) {
      val base = getBase(cell)

      val pathTo = getBestPath(base, cell, currentCells)
      val combined = mutableSetOf<Int>()
      combined.addAll(currentCells)
      combined.addAll(pathTo)

      val isSufficient = pathTo.all { getOpponentAttackPower(getCell(it)) <= floor(getAnts().toDouble() / combined.size.toDouble())}

      if (isSufficient) {
        currentCells.addAll(pathTo)
        newTargets.add(cell)
      }
    }

    fixedTargets.clear()
    fixedTargets.addAll(newTargets)

    return currentCells
  }

  private fun addBeacon(index: Int, strength: Int) {
    if (targets.containsKey(index) && targets[index]!! < strength) targets[index] = strength
    else if (!targets.containsKey(index)) targets[index] = strength
  }

  private fun createLine( path: List<Int>, strength: Int = 1) {
    debug("Doing path: $path")
    val prioritizeEnd = path
    path.forEachIndexed { index, it ->
      addBeacon(it, if (index == path.size - 1 && getCell(it).ownAnts == 0) strength * 2 else strength)
    }
  }

  private fun doWait() {
    actions.add("WAIT")
  }

  private fun doMessage(vararg items: Any) {
    messages.add(items.joinToString(" - "))
  }

  fun defineMovement(targets: Set<Int>) {
    if (targets.isEmpty()) return println("WAIT")
    println(targets.joinToString(";") { "BEACON $it 10" })
  }

  private fun readScores() {
    ownScore = input.nextInt()
    opponentScore = input.nextInt()
  }

  private fun readCells() {
    repeat(numberOfCells) {
      val cell = getCell(it)

      cell.resources = input.nextInt()
      cell.ownAnts = input.nextInt()
      cell.opponentAnts = input.nextInt()

      if (cell.containsEggs()) {
        currentEggs += cell.resources
      } else if (cell.containsCrystal()) {
        currentCrystal += cell.resources
      }
    }
  }
}
