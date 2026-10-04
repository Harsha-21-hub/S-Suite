package com.hesi.slog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AuthState {
    data object SignedOut : AuthState
    /** Signed in; checking with the server whether the account has entered the PIN. */
    data object Checking : AuthState
    /** Signed in but not registered with the PIN yet (or the PIN was changed). */
    data class NeedsPin(val email: String) : AuthState
    data class SignedIn(val uid: String, val email: String, val name: String) : AuthState
}

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Error or info text shown under the form. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** True while sign-up is creating + registering the account (ignore auth changes meanwhile). */
    private var signingUp = false
    private var checkJob: Job? = null

    private val listener = FirebaseAuth.AuthStateListener { refresh() }

    init {
        if (FirebaseRepo.isReady) FirebaseRepo.auth.addAuthStateListener(listener)
        refresh()
    }

    override fun onCleared() {
        if (FirebaseRepo.isReady) FirebaseRepo.auth.removeAuthStateListener(listener)
    }

    /** Works out the screen to show: login, PIN, or the app. */
    private fun refresh() {
        val user = FirebaseRepo.currentUser
        if (user == null) {
            checkJob?.cancel()
            _state.value = AuthState.SignedOut
            return
        }
        if (signingUp) return

        val email = FirebaseRepo.emailOf(user)
        val current = _state.value
        if (current is AuthState.SignedIn && current.uid == user.uid) {
            _state.value = current.copy(name = FirebaseRepo.nameOf(user))
            return
        }

        _state.value = AuthState.Checking
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            val registered = FirebaseRepo.isRegistered(user)
            if (FirebaseRepo.currentUser?.uid != user.uid) return@launch
            _state.value = if (registered) {
                AuthState.SignedIn(user.uid, email, FirebaseRepo.nameOf(user))
            } else {
                AuthState.NeedsPin(email)
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        if (!FirebaseRepo.isReady) {
            _message.value = "Can't connect to the S Log server. This build is missing " +
                    "app/google-services.json - add it (see SETUP.md) and rebuild."
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                block()
            } catch (e: Exception) {
                _message.value = friendlyError(e)
            } finally {
                _busy.value = false
                signingUp = false
                refresh()
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _message.value = "Enter your email and password."
            return
        }
        run { FirebaseRepo.signIn(email, password) }
    }

    fun signUp(name: String, email: String, password: String, pin: String) {
        when {
            name.isBlank() -> _message.value = "Enter your name."
            email.isBlank() -> _message.value = "Enter your email."
            password.length < 6 -> _message.value = "Password must be at least 6 characters."
            pin.isBlank() -> _message.value = "Enter the registration PIN."
            else -> {
                if (_busy.value) return
                signingUp = true
                run { FirebaseRepo.signUp(name, email, password, pin) }
            }
        }
    }

    /** PIN screen for accounts that aren't registered yet. */
    fun submitPin(pin: String) {
        if (pin.isBlank()) {
            _message.value = "Enter the registration PIN."
            return
        }
        run {
            FirebaseRepo.completeRegistration(pin)
            // registered now: go straight in
            FirebaseRepo.currentUser?.let { u ->
                _state.value = AuthState.SignedIn(u.uid, FirebaseRepo.emailOf(u), FirebaseRepo.nameOf(u))
            }
        }
    }

    fun resetPassword(email: String) {
        if (email.isBlank()) {
            _message.value = "Type your email above first, then tap Forgot password."
            return
        }
        run {
            FirebaseRepo.sendPasswordReset(email)
            _message.value = "Password reset link sent to $email."
        }
    }

    fun signOut() {
        if (FirebaseRepo.isReady) FirebaseRepo.signOut()
        _message.value = null
        refresh()
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is WrongPinException -> "Wrong registration PIN."
        is FirebaseAuthWeakPasswordException -> "Password is too weak (min 6 characters)."
        is FirebaseAuthUserCollisionException -> "An account with this email already exists. Log in instead."
        is FirebaseAuthInvalidUserException -> "No account found for this email."
        is FirebaseAuthInvalidCredentialsException -> "Wrong email or password."
        else -> e.localizedMessage ?: "Something went wrong. Check your internet connection."
    }
}
