package com.example.myapplication

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

// унести логику во вьюмодель, выделить state (StateFlow)
class DocumentsFragment : Fragment(R.layout.fragment_documents) {

    private lateinit var recyclerView: RecyclerView
    private lateinit var favoritesButton: ImageView
    private lateinit var filterButton: ImageView
    private lateinit var searchView: SearchView
    private lateinit var emptyText: TextView

    private val viewModel: DocumentsViewModel by viewModels()

    private lateinit var documentsAdapter: DocumentsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recyclerDocuments)
        favoritesButton = view.findViewById(R.id.btnFavorites)
        filterButton = view.findViewById(R.id.btnFilter)
        searchView = view.findViewById(R.id.searchView)
        emptyText = view.findViewById(R.id.tvEmptyDocuments)

        documentsAdapter = DocumentsAdapter(
            items = emptyList(),
            onStarClick = { document ->
                viewModel.toggleFavorite(document)
            },
            onDocumentClick = { document ->
                openDocument(document)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = documentsAdapter

        setupSearch()
        setupFilterButton()
        setupFavoritesButton()
        observeState()
    }

    private fun setupSearch() {
        searchView.queryHint = "Поиск по документам..."

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.onSearchQueryChanged(query.orEmpty())
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.onSearchQueryChanged(newText.orEmpty())
                return true
            }
        })
    }

    private fun setupFilterButton() {
        filterButton.setOnClickListener {
            val currentState = viewModel.state.value

            val options = arrayOf(
                "Фильтр по типу документа",
                if (currentState.selectedType == DocumentType.ALL) "✓ Все типы" else "   Все типы",
                if (currentState.selectedType == DocumentType.CONTRACT) "✓ Договор" else "   Договор",
                if (currentState.selectedType == DocumentType.APPLICATION) "✓ Заявление" else "   Заявление",
                if (currentState.selectedType == DocumentType.CLAIM) "✓ Претензия" else "   Претензия",
                if (currentState.selectedType == DocumentType.COMPLAINT) "✓ Жалоба" else "   Жалоба",
                if (currentState.selectedType == DocumentType.POWER_OF_ATTORNEY) "✓ Доверенность" else "   Доверенность",
                if (currentState.selectedType == DocumentType.ACT) "✓ Акт" else "   Акт",
                if (currentState.selectedType == DocumentType.RECEIPT) "✓ Расписка" else "   Расписка",

                "────────────────────",

                "Сортировка по дате",
                if (currentState.selectedDateFilter == DateFilter.DEFAULT) "✓ Без сортировки" else "   Без сортировки",
                if (currentState.selectedDateFilter == DateFilter.NEW_FIRST) "✓ Сначала новые" else "   Сначала новые",
                if (currentState.selectedDateFilter == DateFilter.OLD_FIRST) "✓ Сначала старые" else "   Сначала старые",

                "────────────────────",

                "Сбросить фильтры"
            )

            AlertDialog.Builder(requireContext())
                .setTitle("Фильтр и сортировка")
                .setItems(options) { _, which ->
                    when (which) {
                        // Заголовок "Фильтр по типу документа"
                        0 -> Unit

                        // Фильтры по типу
                        1 -> viewModel.onDocumentTypeSelected(DocumentType.ALL)
                        2 -> viewModel.onDocumentTypeSelected(DocumentType.CONTRACT)
                        3 -> viewModel.onDocumentTypeSelected(DocumentType.APPLICATION)
                        4 -> viewModel.onDocumentTypeSelected(DocumentType.CLAIM)
                        5 -> viewModel.onDocumentTypeSelected(DocumentType.COMPLAINT)
                        6 -> viewModel.onDocumentTypeSelected(DocumentType.POWER_OF_ATTORNEY)
                        7 -> viewModel.onDocumentTypeSelected(DocumentType.ACT)
                        8 -> viewModel.onDocumentTypeSelected(DocumentType.RECEIPT)

                        // Разделитель
                        9 -> Unit

                        // Заголовок "Сортировка по дате"
                        10 -> Unit

                        // Сортировка
                        11 -> viewModel.onDateFilterSelected(DateFilter.DEFAULT)
                        12 -> viewModel.onDateFilterSelected(DateFilter.NEW_FIRST)
                        13 -> viewModel.onDateFilterSelected(DateFilter.OLD_FIRST)

                        // Разделитель
                        14 -> Unit

                        // Сброс
                        15 -> viewModel.resetFilters()
                    }
                }
                .show()
        }
    }

    private fun setupFavoritesButton() {
        favoritesButton.setOnClickListener {
            viewModel.toggleFavoritesMode()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val visibleDocuments = state.visibleDocuments

                    documentsAdapter.submitList(visibleDocuments)

                    emptyText.visibility =
                        if (visibleDocuments.isEmpty()) View.VISIBLE else View.GONE

                    recyclerView.visibility =
                        if (visibleDocuments.isEmpty()) View.GONE else View.VISIBLE

                    favoritesButton.setImageResource(
                        if (state.isFavoritesMode) {
                            R.drawable.ic_star_filled
                        } else {
                            R.drawable.ic_star_outline
                        }
                    )

                    favoritesButton.setColorFilter(
                        if (state.isFavoritesMode) {
                            requireContext().getColor(R.color.yellow_500)
                        } else {
                            requireContext().getColor(R.color.gray_500)
                        }
                    )
                }
            }
        }
    }

    private fun openDocument(document: Document) {
        AlertDialog.Builder(requireContext())
            .setTitle(document.title)
            .setMessage(document.content)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    inner class DocumentsAdapter(
        private var items: List<Document>,
        private val onStarClick: (Document) -> Unit,
        private val onDocumentClick: (Document) -> Unit
    ) : RecyclerView.Adapter<DocumentsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val date: TextView = view.findViewById(R.id.tvDate)
            val star: ImageView = view.findViewById(R.id.btnStar)
        }

        fun submitList(newItems: List<Document>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_document, parent, false)

            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val document = items[position]

            holder.title.text = document.title
            holder.date.text = "${document.type.displayName} • ${document.date}"

            holder.star.setImageResource(
                if (document.isFavorite) {
                    R.drawable.ic_star_filled
                } else {
                    R.drawable.ic_star_outline
                }
            )

            holder.star.setColorFilter(
                if (document.isFavorite) {
                    holder.itemView.context.getColor(R.color.yellow_500)
                } else {
                    holder.itemView.context.getColor(R.color.gray_400)
                }
            )

            holder.itemView.setOnClickListener {
                onDocumentClick(document)
            }

            holder.star.setOnClickListener {
                onStarClick(document)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}