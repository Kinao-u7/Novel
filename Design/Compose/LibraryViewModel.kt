package com.example.novelreader.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LibraryUiState(
    val books: List<Book> = sampleBooks,
    val categories: List<Category> = listOf(Category("fav", "Favorites"), Category("plan", "Plan to read"), Category("re", "Re-reads")),
    val categoryMap: Map<String, Set<String>> = mapOf(
        "jade" to setOf("fav"), "plum" to setOf("fav", "re"), "dragon" to setOf("plan"),
        "star" to setOf("plan"), "moon" to setOf("plan"), "ash" to setOf("plan"),
    ),
    val selectedCategory: String = "all",
    val filter: ShelfFilter = ShelfFilter.All,
    val sort: SortMode = SortMode.Recent,
    val view: ViewMode = ViewMode.Grid,
    val query: String = "",
    val theme: ThemeMode = ThemeMode.System,
    val openBookId: String? = null,
) {
    val heroBook: Book get() = books.first()
    val filterActive: Boolean get() = filter != ShelfFilter.All || sort != SortMode.Recent

    val visible: List<Book>
        get() {
            val q = query.trim().lowercase()
            val list = books.filter { b ->
                val statusOk = when (filter) {
                    ShelfFilter.All -> true
                    ShelfFilter.Reading -> !b.isFinished && b.chaptersRead > 0
                    ShelfFilter.Finished -> b.isFinished
                    ShelfFilter.Saved -> b.inLibrary
                }
                statusOk &&
                    (selectedCategory == "all" || categoryMap[b.id].orEmpty().contains(selectedCategory)) &&
                    (q.isEmpty() || "${b.title} ${b.author}".lowercase().contains(q))
            }
            return when (sort) {
                SortMode.Recent -> list
                SortMode.Title -> list.sortedBy { it.title }
                SortMode.Author -> list.sortedBy { it.author }
                SortMode.Chapters -> list.sortedByDescending { it.chapters }
                SortMode.Rating -> list.sortedByDescending { it.rating }
            }
        }
}

class LibraryViewModel : ViewModel() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun selectCategory(id: String) = _state.update { it.copy(selectedCategory = id) }
    fun setFilter(f: ShelfFilter) = _state.update { it.copy(filter = f) }
    fun setSort(s: SortMode) = _state.update { it.copy(sort = s) }
    fun resetFilters() = _state.update { it.copy(filter = ShelfFilter.All, sort = SortMode.Recent) }
    fun setView(v: ViewMode) = _state.update { it.copy(view = v) }
    fun setTheme(t: ThemeMode) = _state.update { it.copy(theme = t) }
    fun openBook(id: String) = _state.update { it.copy(openBookId = id) }
    fun closeBook() = _state.update { it.copy(openBookId = null) }

    fun addCategory(name: String) {
        val clean = name.trim().ifEmpty { return }
        val cat = Category("c${System.currentTimeMillis().toString(36)}", clean)
        _state.update { it.copy(categories = it.categories + cat, selectedCategory = cat.id) }
    }

    fun toggleBookCategory(bookId: String, catId: String) = _state.update {
        val cur = it.categoryMap[bookId].orEmpty()
        it.copy(categoryMap = it.categoryMap + (bookId to if (catId in cur) cur - catId else cur + catId))
    }

    fun toggleLibrary(bookId: String) = updateBook(bookId) { it.copy(inLibrary = !it.inLibrary) }

    fun setAllRead(bookId: String, read: Boolean) = updateBook(bookId) {
        if (read) it.copy(chaptersRead = it.chapters, progress = 1f) else it.copy(chaptersRead = 0, progress = 0f)
    }

    fun editDetails(bookId: String, title: String, author: String) = updateBook(bookId) {
        it.copy(title = title.trim().ifEmpty { it.title }, author = author.trim().ifEmpty { it.author })
    }

    private fun updateBook(id: String, f: (Book) -> Book) =
        _state.update { s -> s.copy(books = s.books.map { if (it.id == id) f(it) else it }) }
}
