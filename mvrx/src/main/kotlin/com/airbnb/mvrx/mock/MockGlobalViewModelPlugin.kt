package com.airbnb.mvrx.mock

import androidx.fragment.app.Fragment
import com.airbnb.mvrx.BaseMvRxViewModel
import com.airbnb.mvrx.MvRx
import com.airbnb.mvrx.MvRxState
import com.airbnb.mvrx.MvRxStateFactory
import com.airbnb.mvrx.MvRxView
import com.airbnb.mvrx.RealMvRxStateFactory
import com.airbnb.mvrx.ViewModelContext
import com.airbnb.mvrx.ViewModelDelegateFactory
import com.airbnb.mvrx.lifecycleAwareLazy
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

class MockGlobalViewModelPlugin() : ViewModelDelegateFactory {

    // TODO safe way to get this
    private val configFactory: MockMvRxViewModelConfigFactory =
        (MvRx.viewModelConfigFactory as MockMvRxViewModelConfigFactory)

    // We lock  in the mockBehavior at the time that the Fragment is created (which is when the
    // delegate provider is created). Using the mockbehavior at this time is necessary since it allows
    // consistency in knowing what mock behavior a Fragment will get. If we used the mock behavior
    // at the time when the viewmodel is created it would be some point in the future that is harder
    // to determine and control for.
    private val mockBehavior = configFactory.mockBehavior

    override fun <S : MvRxState, T : Fragment, VM : BaseMvRxViewModel<S>> createLazyViewModel(
        stateClass: KClass<S>,
        view: T,
        viewModelProperty: KProperty<*>,
        existingViewModel: Boolean,
        viewModelProvider: (stateFactory: MvRxStateFactory<VM, S>) -> VM
    ): Lazy<VM> where T : MvRxView {
        return lifecycleAwareLazy(view) {
            val mockState: S? =
                if (mockBehavior != null && mockBehavior.initialState != MockBehavior.InitialState.None) {
                    MvRxMocks.mockStateHolder.getMockedState(
                        view = view,
                        viewModelProperty = viewModelProperty,
                        existingViewModel = existingViewModel,
                        stateClass = stateClass.java,
                        forceMockExistingViewModel = mockBehavior.initialState == MockBehavior.InitialState.ForceMockExistingViewModel
                    )
                } else {
                    null
                }

            configFactory.withMockBehavior(
                mockBehavior
            ) {
                viewModelProvider(stateFactory(mockState))
                    .apply { subscribe(view, subscriber = { view.postInvalidate() }) }
                    .also { vm ->
                        if (mockState != null && mockBehavior?.initialState == MockBehavior.InitialState.Full) {
                            // Custom viewmodel factories can override initial state, so we also force state on the viewmodel
                            // to be the expected mocked value after the ViewModel has been created.

                            val stateStore =
                                vm.config.stateStore as? MockableStateStore
                                    ?: error("Expected a mockable state store for 'Full' mock behavior.")

                            require(stateStore.mockBehavior.stateStoreBehavior == MockBehavior.StateStoreBehavior.Scriptable) {
                                "Full mock state requires that the state store be set to scriptable to " +
                                        "guarantee that state is frozen on the mock and not allowed to be changed by the view."
                            }

                            stateStore.next(mockState)
                        }
                    }
            }
        }.also { viewModelDelegate ->
            if (mockBehavior != null) {
                // If a view is being mocked then one of its view models may depend on another,
                // in which case the dependent needs to be initialized after the VM it depends on.
                // Tracking all view model delegates created for a view allows us to
                // initialize existing view models first, since Fragment view models
                // may depend on existing view models.
                MvRxMocks.mockStateHolder.addViewModelDelegate(
                    view = view,
                    existingViewModel = existingViewModel,
                    viewModelProperty = viewModelProperty,
                    viewModelDelegate = viewModelDelegate
                )
            }

        }
    }


    private fun <S : MvRxState, VM : BaseMvRxViewModel<S>> stateFactory(
        mockState: S?
    ): MvRxStateFactory<VM, S> {
        return if (mockState == null) {
            RealMvRxStateFactory()
        } else {
            object : MvRxStateFactory<VM, S> {
                override fun createInitialState(
                    viewModelClass: Class<out VM>,
                    stateClass: Class<out S>,
                    viewModelContext: ViewModelContext,
                    stateRestorer: (S) -> S
                ): S {
                    return mockState
                }
            }
        }
    }


}