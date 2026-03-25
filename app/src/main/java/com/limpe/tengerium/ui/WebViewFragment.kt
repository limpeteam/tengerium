package com.limpe.tengerium.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.FragmentWebViewBinding

class WebViewFragment : Fragment() {

    private var _binding: FragmentWebViewBinding? = null
    private val binding get() = _binding!!

    private var initialHost: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWebViewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString("url") ?: ""
        val title = arguments?.getString("title") ?: getString(R.string.services_title)

        initialHost = url.toUri().host
        binding.toolbar.title = title
        
        val mainFragment = parentFragment as? MainFragment ?: parentFragment?.parentFragment as? MainFragment
        val isTablet = mainFragment?.isTablet == true

        if (isTablet) {
            binding.toolbar.navigationIcon = null
        } else {
            binding.toolbar.setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        Glide.with(this)
            .asGif()
            .load(R.drawable.butterfly)
            .into(binding.ivLoading)

        setupWebView()
        binding.webView.loadUrl(url)
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host ?: return false
                
                val baseHost = initialHost ?: return false
                
                // Разрешаем основной хост и любые его поддомены
                val isAllowed = host == baseHost || host.endsWith(".$baseHost")
                
                if (!isAllowed) {
                    showExitDialog(uri)
                    return true // Блокируем переход в самом WebView
                }
                
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                _binding?.loadingCard?.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                _binding?.loadingCard?.isVisible = false
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    _binding?.loadingCard?.isVisible = false
                }
            }
        }
    }

    private fun showExitDialog(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.leaving_app_title)
            .setMessage(getString(R.string.leaving_app_message, uri.toString()))
            .setPositiveButton(R.string.leave) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }
            .setNegativeButton(R.string.stay, null)
            .show()
    }

    override fun onDestroyView() {
        // Важно: очищаем WebView, чтобы он не слал события в пустоту
        binding.webView.stopLoading()
        binding.webView.webViewClient = WebViewClient()
        binding.webView.webChromeClient = WebChromeClient()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(url: String, title: String): WebViewFragment {
            return WebViewFragment().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                    putString("title", title)
                }
            }
        }
    }
}
