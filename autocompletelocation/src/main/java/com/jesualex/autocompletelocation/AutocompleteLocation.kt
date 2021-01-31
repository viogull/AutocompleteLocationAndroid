package com.jesualex.autocompletelocation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.support.v4.content.ContextCompat
import android.support.v7.widget.AppCompatAutoCompleteTextView
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.Toast

import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.LocationBias
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.TypeFilter
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse
import com.google.android.libraries.places.api.net.PlacesClient

class AutocompleteLocation @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = R.attr.autoCompleteTextViewStyle
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {
    private val mCloseIcon: Drawable
    private val mAutocompleteAdapter: AutocompleteAdapter
    private val placesClient: PlacesClient
    private val token: AutocompleteSessionToken
    private val autocompleteClickListener: AdapterView.OnItemClickListener
    private val editorActionListener: OnEditorActionListener
    private val onTouchListener: OnTouchListener
    private val textWatcher: TextWatcher

    private var mAutocompleteLocationListener: AutocompleteLocationListener? = null
    private var onSearchListener: OnSearchListener? = null
    private var placeListener: OnPlaceLoadListener? = null
    private var placeFields: List<Place.Field> = PlaceUtils.defaultFields
    private var bounds: LocationBias? = null
    private var country: String? = null

    private lateinit var successListener: OnSuccessListener<FindAutocompletePredictionsResponse>
    private lateinit var failureListener: OnFailureListener

    init {
        val resources = context.resources
        val typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.AutocompleteLocation, defStyleAttr, 0)
        var background = typedArray.getDrawable(R.styleable.AutocompleteLocation_background_layout)

        if (background == null) {
            background = ContextCompat.getDrawable(context, R.drawable.bg_rounded_white)
        }

        if (!typedArray.hasValue(R.styleable.AutocompleteLocation_android_hint)) {
            hint = resources.getString(R.string.default_hint_text)
        }

        if (!typedArray.hasValue(R.styleable.AutocompleteLocation_android_textColorHint)) {
            setHintTextColor(ContextCompat.getColor(context, R.color.default_hint_text))
        }

        if (!typedArray.hasValue(R.styleable.AutocompleteLocation_android_textColor)) {
            setTextColor(ContextCompat.getColor(context, R.color.default_text))
        }

        if (!typedArray.hasValue(R.styleable.AutocompleteLocation_android_maxLines)) {
            maxLines = resources.getInteger(R.integer.default_max_lines)
        }

        if (!typedArray.hasValue(R.styleable.AutocompleteLocation_android_inputType)) {
            isHorizontalScrollBarEnabled = true
            inputType = InputType.TYPE_CLASS_TEXT
        }

        var paddingStart: Int
        var paddingTop: Int
        var paddingEnd: Int
        var paddingBottom: Int
        paddingBottom = typedArray.getDimensionPixelSize(
                R.styleable.AutocompleteLocation_android_padding, resources.getDimensionPixelSize(R.dimen.default_padding))
        paddingEnd = paddingBottom
        paddingTop = paddingEnd
        paddingStart = paddingTop
        paddingStart = typedArray.getDimensionPixelSize(
                R.styleable.AutocompleteLocation_android_paddingStart, paddingStart)
        paddingTop = typedArray.getDimensionPixelSize(
                R.styleable.AutocompleteLocation_android_paddingTop, paddingTop)
        paddingEnd = typedArray.getDimensionPixelSize(
                R.styleable.AutocompleteLocation_android_paddingEnd, paddingEnd)
        paddingBottom = typedArray.getDimensionPixelSize(
                R.styleable.AutocompleteLocation_android_paddingBottom, paddingBottom)

        country = typedArray.getString(R.styleable.AutocompleteLocation_countryCode)
        mCloseIcon = ContextCompat.getDrawable(context, typedArray.getResourceId(
                R.styleable.AutocompleteLocation_closeIcon, R.drawable.ic_close))!!

        typedArray.recycle()
        setBackground(background)
        setPadding(paddingStart, paddingTop, paddingEnd, paddingBottom)
        imeOptions = EditorInfo.IME_ACTION_SEARCH

        placesClient = PlaceUtils.getPlacesClient(getContext())
        token = AutocompleteSessionToken.newInstance()
        mAutocompleteAdapter = AutocompleteAdapter(getContext())

        autocompleteClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            UIUtils.hideKeyboard(this@AutocompleteLocation.context, this@AutocompleteLocation)
            val p = mAutocompleteAdapter.getItem(position)

            if (mAutocompleteLocationListener != null) {
                mAutocompleteLocationListener!!.onItemSelected(p)
            }

            if (p != null && placeListener != null) {
                PlaceUtils.getPlace(placesClient, p.placeId, placeFields, placeListener!!)
            }
        }

        editorActionListener = OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                UIUtils.hideKeyboard(this@AutocompleteLocation.context, this@AutocompleteLocation)

                if (onSearchListener != null) {
                    onSearchListener!!.onSearch(text.toString(), mAutocompleteAdapter.resultList)
                }

                mAutocompleteAdapter.notifyDataSetInvalidated()
                return@OnEditorActionListener true
            }

            false
        }

        onTouchListener = OnTouchListener { _, motionEvent ->
            if (motionEvent.x > (this@AutocompleteLocation.width
                            - this@AutocompleteLocation.paddingRight
                            - mCloseIcon.intrinsicWidth)) {
                this@AutocompleteLocation.setCompoundDrawables(null, null, null, null)
                this@AutocompleteLocation.setText("")
            }

            if (enoughToFilter()) {
                showDropDown()
            }

            false
        }

        textWatcher = object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                val isEmpty = charSequence.toString().isEmpty()

                setCompoundDrawablesWithIntrinsicBounds(
                        null,
                        null,
                        if(isEmpty) null else mCloseIcon,
                        null
                )

                if (mAutocompleteLocationListener != null && isEmpty) {
                    mAutocompleteLocationListener!!.onTextClear()
                }
            }

            override fun afterTextChanged(editable: Editable) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        this.addTextChangedListener(textWatcher)
        this.setOnTouchListener(onTouchListener)
        this.onItemClickListener = autocompleteClickListener
        this.setAdapter(mAutocompleteAdapter)
        this.setOnEditorActionListener(editorActionListener)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            this.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        } else {
            this.setCompoundDrawablesWithIntrinsicBounds(null, null,
                    if (this@AutocompleteLocation.text.toString().isEmpty()) null else mCloseIcon, null)
        }
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_BACK && isPopupShowing && UIUtils.hideKeyboard(context, findFocus())) {
            true
        } else super.onKeyPreIme(keyCode, event)

    }

    override fun performFiltering(text: CharSequence, keyCode: Int) {
        if (isEnabled) {
            getAutocomplete(text)
        }
    }

    override fun convertSelectionToString(selectedItem: Any): CharSequence {
        return (selectedItem as? AutocompletePrediction)?.getFullText(null)
                ?: super.convertSelectionToString(selectedItem)
    }

    private fun getAutocomplete(constraint: CharSequence) {
        val request = FindAutocompletePredictionsRequest.builder()
                .setLocationBias(bounds)
                .setCountry(country)
                .setTypeFilter(TypeFilter.CITIES)
                .setSessionToken(token)
                .setQuery(constraint.toString())
                .build()

        placesClient.findAutocompletePredictions(request)
                .addOnSuccessListener(getSuccessListener())
                .addOnFailureListener(getFailureListener())
    }

    private fun getSuccessListener(): OnSuccessListener<FindAutocompletePredictionsResponse> {
        if (!this::successListener.isInitialized) {
            successListener = OnSuccessListener { resp ->
                mAutocompleteAdapter.resultList = resp.autocompletePredictions
                onFilterComplete(mAutocompleteAdapter.count)
            }
        }

        return successListener
    }

    private fun getFailureListener(): OnFailureListener {
        if (!this::failureListener.isInitialized) {
            failureListener = OnFailureListener { e ->
                mAutocompleteAdapter.notifyDataSetInvalidated()
                Toast.makeText(context, "Error contacting API: " + e.message,
                        Toast.LENGTH_SHORT).show()
            }
        }

        return failureListener
    }

    fun setAutoCompleteTextListener(autoCompleteLocationListener: AutocompleteLocationListener) {
        mAutocompleteLocationListener = autoCompleteLocationListener
    }

    fun setCountry(country: String) {
        this.country = country
    }

    fun setLocationBias(locationBias: LocationBias) {
        this.bounds = locationBias
    }

    fun setOnSearchListener(onSearchListener: OnSearchListener) {
        this.onSearchListener = onSearchListener
    }

    fun setPlaceListener(placeListener: OnPlaceLoadListener) {
        this.placeListener = placeListener
    }

    fun setPlaceFields(placeFields: List<Place.Field>) {
        this.placeFields = placeFields
    }
}
