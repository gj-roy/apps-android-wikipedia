package org.wikipedia.random

import android.app.ActivityOptions
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.schedulers.Schedulers
import org.wikipedia.Constants
import org.wikipedia.Constants.InvokeSource
import org.wikipedia.R
import org.wikipedia.WikipediaApp
import org.wikipedia.database.AppDatabase
import org.wikipedia.databinding.FragmentRandomBinding
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.events.ArticleSavedOrDeletedEvent
import org.wikipedia.extensions.parcelable
import org.wikipedia.history.HistoryEntry
import org.wikipedia.page.PageActivity
import org.wikipedia.page.PageTitle
import org.wikipedia.readinglist.LongPressMenu
import org.wikipedia.readinglist.ReadingListBehaviorsUtil
import org.wikipedia.readinglist.database.ReadingListPage
import org.wikipedia.util.AnimationUtil.PagerTransformer
import org.wikipedia.util.DimenUtil
import org.wikipedia.util.FeedbackUtil
import org.wikipedia.util.log.L
import org.wikipedia.views.PositionAwareFragmentStateAdapter

class RandomFragment : Fragment() {

    companion object {
        const val DEFAULT_PAGER_TAB = 0
        const val PAGER_OFFSCREEN_PAGE_LIMIT = 2
        const val ENABLED_BACK_BUTTON_ALPHA = 1f
        const val DISABLED_BACK_BUTTON_ALPHA = 0.5f

        fun newInstance(wikiSite: WikiSite, invokeSource: InvokeSource) = RandomFragment().apply {
            arguments = bundleOf(
                Constants.ARG_WIKISITE to wikiSite,
                Constants.INTENT_EXTRA_INVOKE_SOURCE to invokeSource
            )
        }
    }

    private var _binding: FragmentRandomBinding? = null
    private val binding get() = _binding!!
    private val disposables = CompositeDisposable()
    private val viewPagerListener: ViewPagerListener = ViewPagerListener()

    private lateinit var wikiSite: WikiSite
    private val topTitle get() = getTopChild()?.title
    private var saveButtonState = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        _binding = FragmentRandomBinding.inflate(inflater, container, false)
        val view = binding.root

        FeedbackUtil.setButtonLongPressToast(binding.randomNextButton, binding.randomSaveButton)

        wikiSite = requireArguments().parcelable(Constants.ARG_WIKISITE)!!

        binding.randomItemPager.offscreenPageLimit = 2
        binding.randomItemPager.adapter = RandomItemAdapter(this)
        binding.randomItemPager.setPageTransformer(PagerTransformer(resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL))
        binding.randomItemPager.registerOnPageChangeCallback(viewPagerListener)

        binding.randomNextButton.setOnClickListener { onNextClick() }
        binding.randomBackButton.setOnClickListener { onBackClick() }
        binding.randomSaveButton.setOnClickListener { onSaveShareClick() }

        disposables.add(WikipediaApp.instance.bus.subscribe(EventBusConsumer()))

        updateSaveShareButton()
        updateBackButton(DEFAULT_PAGER_TAB)

        if (savedInstanceState != null && binding.randomItemPager.currentItem == DEFAULT_PAGER_TAB && topTitle != null) {
            updateSaveShareButton(topTitle)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        updateSaveShareButton(topTitle)
    }

    override fun onDestroyView() {
        disposables.clear()
        binding.randomItemPager.unregisterOnPageChangeCallback(viewPagerListener)
        _binding = null
        super.onDestroyView()
    }

    private fun onNextClick() {
        if (binding.randomNextButton.drawable is Animatable) {
            (binding.randomNextButton.drawable as Animatable).start()
        }

        viewPagerListener.setNextPageSelectedAutomatic()
        binding.randomItemPager.setCurrentItem(binding.randomItemPager.currentItem + 1, true)
    }

    private fun onBackClick() {
        viewPagerListener.setNextPageSelectedAutomatic()

        if (binding.randomItemPager.currentItem > DEFAULT_PAGER_TAB) {
            binding.randomItemPager.setCurrentItem(binding.randomItemPager.currentItem - 1, true)
        }
    }

    private fun onSaveShareClick() {
        val title = topTitle ?: return

        if (saveButtonState) {
            LongPressMenu(binding.randomSaveButton, existsInAnyList = false, callback = object : LongPressMenu.Callback {
                override fun onAddRequest(entry: HistoryEntry, addToDefault: Boolean) {
                    ReadingListBehaviorsUtil.addToDefaultList(requireActivity(), title, addToDefault, InvokeSource.RANDOM_ACTIVITY) {
                        updateSaveShareButton(title)
                    }
                }

                override fun onMoveRequest(page: ReadingListPage?, entry: HistoryEntry) {
                    page?.let {
                        ReadingListBehaviorsUtil.moveToList(requireActivity(), page.listId, title, InvokeSource.RANDOM_ACTIVITY) {
                            updateSaveShareButton()
                        }
                    }
                }
            }).show(HistoryEntry(title, HistoryEntry.SOURCE_RANDOM))
        } else {
            ReadingListBehaviorsUtil.addToDefaultList(requireActivity(), title, true, InvokeSource.RANDOM_ACTIVITY) {
                updateSaveShareButton(title)
            }
        }
    }

    fun onSelectPage(title: PageTitle, sharedElements: Array<Pair<View, String>>) {
        val options =
            ActivityOptions.makeSceneTransitionAnimation(requireActivity(), *sharedElements)
        val intent = PageActivity.newIntentForNewTab(
            requireContext(),
            HistoryEntry(title, HistoryEntry.SOURCE_RANDOM), title
        )

        if (sharedElements.isNotEmpty()) {
            intent.putExtra(Constants.INTENT_EXTRA_HAS_TRANSITION_ANIM, true)
        }

        startActivity(
            intent,
            if (DimenUtil.isLandscape(requireContext()) || sharedElements.isEmpty()) null else options.toBundle()
        )
    }

    private fun updateBackButton(pagerPosition: Int) {
        binding.randomBackButton.isClickable = pagerPosition != DEFAULT_PAGER_TAB
        binding.randomBackButton.alpha =
            if (pagerPosition == DEFAULT_PAGER_TAB) DISABLED_BACK_BUTTON_ALPHA else ENABLED_BACK_BUTTON_ALPHA
    }

    private fun updateSaveShareButton(title: PageTitle?) {
        if (title == null) {
            return
        }

        val d = Observable.fromCallable {
            AppDatabase.instance.readingListPageDao().findPageInAnyList(title) != null
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ exists: Boolean ->
                saveButtonState = exists
                val img =
                    if (saveButtonState) R.drawable.ic_bookmark_white_24dp else R.drawable.ic_bookmark_border_white_24dp
                binding.randomSaveButton.setImageResource(img)
            }, { t ->
                L.w(t)
            })

        disposables.add(d)
    }

    private fun updateSaveShareButton() {
        val enable = getTopChild()?.isLoadComplete ?: false

        binding.randomSaveButton.isClickable = enable
        binding.randomSaveButton.alpha =
            if (enable) ENABLED_BACK_BUTTON_ALPHA else DISABLED_BACK_BUTTON_ALPHA
    }

    fun onChildLoaded() {
        updateSaveShareButton()
    }

    private fun getTopChild(): RandomItemFragment? {
        val adapter = binding.randomItemPager.adapter as? RandomItemAdapter
        return adapter?.getFragmentAt(binding.randomItemPager.currentItem) as? RandomItemFragment
    }

    private inner class RandomItemAdapter(fragment: Fragment) :
        PositionAwareFragmentStateAdapter(fragment) {
        override fun getItemCount(): Int {
            return Int.MAX_VALUE
        }

        override fun createFragment(position: Int): Fragment {
            return RandomItemFragment.newInstance(wikiSite)
        }
    }

    private inner class ViewPagerListener : OnPageChangeCallback() {
        private var prevPosition = DEFAULT_PAGER_TAB
        private var nextPageSelectedAutomatic = false

        fun setNextPageSelectedAutomatic() {
            nextPageSelectedAutomatic = true
        }

        override fun onPageSelected(position: Int) {
            updateBackButton(position)
            updateSaveShareButton(topTitle)

            nextPageSelectedAutomatic = false
            prevPosition = position

            updateSaveShareButton()

            val storedOffScreenPagesCount = binding.randomItemPager.offscreenPageLimit * 2 + 1
            if (position >= storedOffScreenPagesCount) {
                (binding.randomItemPager.adapter as RandomItemAdapter).removeFragmentAt(position - storedOffScreenPagesCount)
            }
        }
    }

    private inner class EventBusConsumer : Consumer<Any> {
        override fun accept(event: Any) {
            if (event is ArticleSavedOrDeletedEvent) {
                if (!isAdded || topTitle == null) {
                    return
                }
                for (page in event.pages) {
                    if (page.apiTitle == topTitle?.prefixedText && page.wiki.languageCode == topTitle?.wikiSite?.languageCode) {
                        updateSaveShareButton(topTitle)
                    }
                }
            }
        }
    }
}
