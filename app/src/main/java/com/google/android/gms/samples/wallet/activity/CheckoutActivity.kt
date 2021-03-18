/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.samples.wallet.activity

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.samples.wallet.R
import com.google.android.gms.samples.wallet.util.Json
import com.google.android.gms.samples.wallet.util.PaymentsUtil
import com.google.android.gms.wallet.*
import kotlinx.android.synthetic.main.activity_checkout.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.*


/**
 * Checkout implementation for the app
 */


class CheckoutActivity : Activity() {

    private val SHIPPING_COST_CENTS = 9 * PaymentsUtil.CENTS.toLong()

    /**
     * A client for interacting with the Google Pay API.
     *
     * @see [PaymentsClient](https://developers.google.com/android/reference/com/google/android/gms/wallet/PaymentsClient)
     */
    private lateinit var paymentsClient: PaymentsClient

    private lateinit var garmentList: JSONArray
    private lateinit var selectedGarment: JSONObject

    /**
     * Arbitrarily-picked constant integer you define to track a request for payment data activity.
     *
     * @value #LOAD_PAYMENT_DATA_REQUEST_CODE
     */
    private val LOAD_PAYMENT_DATA_REQUEST_CODE = 991

    /**
     * Initialize the Google Pay API on creation of the activity
     *
     * @see Activity.onCreate
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_checkout)

        // Set up the mock information for our item in the UI.
        selectedGarment = fetchRandomGarment()
        displayGarment(selectedGarment)

        // Initialize a Google Pay API client for an environment suitable for testing.
        // It's recommended to create the PaymentsClient object inside of the onCreate method.
        paymentsClient = PaymentsUtil.createPaymentsClient(this)
        possiblyShowGooglePayButton()

        googlePayButton.setOnClickListener { requestPayment() }
    }

    /**
     * Determine the viewer's ability to pay with a payment method supported by your app and display a
     * Google Pay payment button.
     *
     * @see [](https://developers.google.com/android/reference/com/google/android/gms/wallet/PaymentsClient.html.isReadyToPay
    ) */
    private fun possiblyShowGooglePayButton() {

        val isReadyToPayJson = PaymentsUtil.isReadyToPayRequest() ?: return
        val request = IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()) ?: return

        // The call to isReadyToPay is asynchronous and returns a Task. We need to provide an
        // OnCompleteListener to be triggered when the result of the call is known.
        val task = paymentsClient.isReadyToPay(request)
        task.addOnCompleteListener { completedTask ->
            try {
                completedTask.getResult(ApiException::class.java)?.let(::setGooglePayAvailable)
            } catch (exception: ApiException) {
                // Process error
                Log.w("isReadyToPay failed", exception)
            }
        }
    }

    /**
     * If isReadyToPay returned `true`, show the button and hide the "checking" text. Otherwise,
     * notify the user that Google Pay is not available. Please adjust to fit in with your current
     * user flow. You are not required to explicitly let the user know if isReadyToPay returns `false`.
     *
     * @param available isReadyToPay API response.
     */
    private fun setGooglePayAvailable(available: Boolean) {
        if (available) {
            googlePayButton.visibility = View.VISIBLE
        } else {
            Toast.makeText(
                    this,
                    "Unfortunately, Google Pay is not available on this device",
                    Toast.LENGTH_LONG).show();
        }
    }

    private fun requestPayment() {

        // Disables the button to prevent multiple clicks.
        googlePayButton.isClickable = false

        // The price provided to the API should include taxes and shipping.
        // This price is not displayed to the user.
        val garmentPrice = selectedGarment.getDouble("price")
        val priceCents = Math.round(garmentPrice * PaymentsUtil.CENTS.toLong()) + SHIPPING_COST_CENTS

        val paymentDataRequestJson = PaymentsUtil.getPaymentDataRequest(priceCents)
        if (paymentDataRequestJson == null) {
            Log.e("RequestPayment", "Can't fetch payment data request")
            return
        }
        val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())

        // Since loadPaymentData may show the UI asking the user to select a payment method, we use
        // AutoResolveHelper to wait for the user interacting with it. Once completed,
        // onActivityResult will be called with the result.
        if (request != null) {
            AutoResolveHelper.resolveTask(
                    paymentsClient.loadPaymentData(request), this, LOAD_PAYMENT_DATA_REQUEST_CODE)
        }
    }

    /**
     * Handle a resolved activity from the Google Pay payment sheet.
     *
     * @param requestCode Request code originally supplied to AutoResolveHelper in requestPayment().
     * @param resultCode Result code returned by the Google Pay API.
     * @param data Intent from the Google Pay API containing payment or error data.
     * @see [Getting a result
     * from an Activity](https://developer.android.com/training/basics/intents/result)
     */
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            // Value passed in AutoResolveHelper
            LOAD_PAYMENT_DATA_REQUEST_CODE -> {
                when (resultCode) {
                    RESULT_OK ->
                        data?.let { intent ->
                            PaymentData.getFromIntent(intent)?.let(::handlePaymentSuccess)
                        }

                    RESULT_CANCELED -> {
                        // The user cancelled the payment attempt
                    }

                    AutoResolveHelper.RESULT_ERROR -> {
                        AutoResolveHelper.getStatusFromIntent(data)?.let {
                            handleError(it.statusCode)
                            Log.e(TAG, "onActivityResult: " + it.status)
                        }
                    }
                }

                // Re-enables the Google Pay payment button.
                googlePayButton.isClickable = true
            }
        }
    }

    /**
     * PaymentData response object contains the payment information, as well as any additional
     * requested information, such as billing and shipping address.
     *
     * @param paymentData A response object returned by Google after a payer approves payment.
     * @see [Payment
     * Data](https://developers.google.com/pay/api/android/reference/object.PaymentData)
     */
    private fun handlePaymentSuccess(paymentData: PaymentData) {
        val paymentInformation = paymentData.toJson() ?: return

        try {
            // Token will be null if PaymentDataRequest was not constructed using fromJson(String).
            val paymentMethodData = JSONObject(paymentInformation).getJSONObject("paymentMethodData")
            val billingName = paymentMethodData.getJSONObject("info")
                    .getJSONObject("billingAddress").getString("name")
            Log.d("BillingName", billingName)

            Toast.makeText(this, getString(R.string.payments_show_name, billingName), Toast.LENGTH_LONG).show()

            // Logging token string.
            Log.d("GooglePaymentToken", paymentMethodData
                    .getJSONObject("tokenizationData")
                    .getString("token"))


            val jsonObj = paymentMethodData
                    .getJSONObject("tokenizationData")
                    .getString("token")

            val obj = JSONObject(jsonObj)
            val successToken = obj.getString("signature")


            Log.e("TOKEN", successToken)
            val bytes = jsonObj.toByteArray(charset("UTF-8"))

          val base64 = String(Base64.encode(bytes, 0))

            val decoded = String(Base64.decode(base64, 0))
          //val base64 = String(Base64.encode(bytes, 2))
           // eyJzaWduYXR1cmUiOiJNRVVDSUdndTR5ZEtCd2FiYzh2UE53SmZOTlpEcy9aTXR2ZTJHSzdiZzVFWkZpcWlBaUVBdEwxeXVyODNMQldvaTJ3ZXM5cXNKODJSam5UemsxdDR0dkx2NkJlNC9Zd1x1MDAzZCIsInByb3RvY29sVmVyc2lvbiI6IkVDdjEiLCJzaWduZWRNZXNzYWdlIjoie1wiZW5jcnlwdGVkTWVzc2FnZVwiOlwieFAxbjJWY2FvMExMaklmZHZUdmFRMDlxVlVsaCs1cE1pSnhIMjJEZFltMjV0SThDWE4vZ1dJM3JsL3NzR094bi94blRGa1lCbnNoSG5GTEpURUZqdG5BendzL1NzTUF6NVYyTTQrem85bURUSjVZYi80OUc2aGJjRTl3OXMvWFp0Nmw2bldjVU42MFEzcGdLa1pDQ1hva3ppUU9xWFZ2aWdsZ1M0Q2NLV1JFdCs1WVlLcFNKeW9ZdEczYUc0RHRQRElxTTJzaGFsUVZkdWZMckx2K1RPREd6Y1dQWGdxRjJKQ3NtSnRmS25SZ3pNSC80WlJSQkI2OVU3ejE4VUoxOFNxa3NPanc3bjRRcnl6VFRib0R4VHdwV09XRHZkK2oySVJNcTIyc2dNb1loM0Y2d1VSUFVuWjBkTjFsVlU4akcrL0RwZEh5SHVnUURyVjRVVjhOUFZLdDNleWlobStDOGJZdWoweEExY0R3SC9wV25SWm1RUnpUd0o5ZUVYQzBHL2QvTitSUHZ0cHJUZ0RaLzhDcU5PdUN6MFlUdXJlRmtrZ2J3b2U5bUVpaE5RZUZOei83Zmppd2xIV2E3cHZYSFQwYWtFaGVYQTZadlpSUEpQVHU3MTVGSmpuekZ5elc5TFpPK3JVYXRQRms3S2xoQU4xUjVHUm1MUVhzRUplUEp5SUI1N3pMM3Fwc0FmSWJjNTc4bDZvRklVUW8vTS96OW1CVXpScXVnOXN6VkZWU3NTVGZ6MnVlZzJyTXl0cXFZKzNkVG1lVThPN0dNUWlzVGZnR2JGY1pwdlZDallvK3cwZ0lEaVpJTUs2Y0E1b3lHaG5xbmJxWTdWdy9DZ3BzTFJCd00vT3F0anhMNm9hKzZRdWtScjFaMUhmZ1xcdTAwM2RcIixcImVwaGVtZXJhbFB1YmxpY0tleVwiOlwiQkYyUnBXNmJPY01Zd2Rzbm5kTlF5NExVc1lGTXhNVWs4SFMvU2J6bkg5S1BwVVlJUHJrWmt3WkFoWXJ2RHRxZmd0Ym1XR3BRMjlpSDFBeFNUSFp1WWN3XFx1MDAzZFwiLFwidGFnXCI6XCJMeXJoTHlNem02NFl6UlE5dEpFdHk2NWNKdm5xVG5CaFJwSG5oZU8vR2dnXFx1MDAzZFwifSJ9

            //eyJzaWduYXR1cmUiOiJNRVFDSUVBYWoxbEwyZE5qOEhHQTQrdFRscFp4US9HRlQxWmhyTmdLUUdYT2FYby9BaUJpOThQeEU5NGFZMWhDV3BRelZBUmhQekdKZTdLT21nc0pBdFBJdytrV1hnXHUwMDNkXHUwMDNkIiwicHJvdG9jb2xWZXJzaW9uIjoiRUN2MSIsInNpZ25lZE1lc3NhZ2UiOiJ7XCJlbmNyeXB0ZWRNZXNzYWdlXCI6XCJ4azdkOVcyTkZIMW9ycHZwVU5zS1cyaXVoQlUydTdKaW9oYXFSZTl6VEZXb3c5eHBEbVJnTWYvRHhxMWVwaWc1UEFLVVB5RmFiV0NlY25ESEZmQlBkQXplMS9UcklwWmxhakc1WlVUcms5SFlRUEMrQ2YzVmgyTGJ2RmdLOXREYXhRTXAzMWlxTVFUT0Nyc0JYMmxLRnVNeUpVbDZmakRQcjU0N0REdDg5Q2RZQUYzSW80SlgycXVaYnNmTEZoRUl6emFQOFc5WWVSazRSV3FZUDJENnBmQzhLZjF0NmVVcWtxcU9DVWNyS1IwUkZGeFhjRjFWak1PRUVWSFRIdzhMM2Irc1d2bERRcllNZml0RFFmcnRBYU9GUjRCUSt5U3pTS2V1RWVzTlpDVGI2a2JCRXhCcmVUUTEvd1VzazJ4UnJqaGgwMHlmc0xQdS90WUloNC9lVzJnbVpGRW9EaC9kMzMvckcxY2hrc3lmVVhsOEFSclVYaWpaQmhBN1FHeHVrNTZ5RW5nMWE4Rjd0RHdVUTFKZnQwaHlCc09BWXFqZEZQT3NlTDdhT1Jwb2F3RE1uZm1BcVc5cXhDM252QXR3S3F3UVFuOVM5U290NzB6N05JQzJTeURqRHo1a0JnMFEyM1ZyQ2NpK281RkFOZ3FLYmVsRExiMThzZlBSQi9wNzdtSTJKQ1VybENYbnpySGpQeUdQZUhmTGRLeEp5RW5xSmt1MzdDeG9hdkNVSHM4ZjB3d2JFOHVsd1p2UTdlTzhmWkdtZ20yT3NsWFIrd1xcdTAwM2RcXHUwMDNkXCIsXCJlcGhlbWVyYWxQdWJsaWNLZXlcIjpcIkJJWGNmNFBua3lTejZvNU1iWkxkQ21jMDVrZ0JoRmZIZC80cnZKZ2t5NVFZU1JyUFhocTR3K2Q2a1JsYkFiUmg0UC9FeWRmRU9XZFFydC8zMGsrZXp6Z1xcdTAwM2RcIixcInRhZ1wiOlwiazB6YjhvVUNpVXlzVHU5OElXbVFOTlZwbklIMWtobjQrYkJsaENETWkyWVxcdTAwM2RcIn0ifQ==
            //ENCODE
            //ENCODE

           // val base64_str: String = Base64.encode(jsonObj.toByteArray(charset("UTF-8")),0).toString()

            Log.e("BASE64", base64)
          // Log.e("base64_str", base64_str)
            Log.e("BASE64 DECODE", decoded)





        } catch (e: JSONException) {
            Log.e("handlePaymentSuccess", "Error: " + e.toString())
        }

    }

    /**
     * At this stage, the user has already seen a popup informing them an error occurred. Normally,
     * only logging is required.
     *
     * @param statusCode will hold the value of any constant from CommonStatusCode or one of the
     * WalletConstants.ERROR_CODE_* constants.
     * @see [
     * Wallet Constants Library](https://developers.google.com/android/reference/com/google/android/gms/wallet/WalletConstants.constant-summary)
     */
    private fun handleError(statusCode: Int) {
        Log.w("loadPaymentData failed", String.format("Error code: %d", statusCode))
    }

    private fun fetchRandomGarment(): JSONObject {
        if (!::garmentList.isInitialized) {
            garmentList = Json.readFromResources(this, R.raw.tshirts)
        }

        val randomIndex: Int = Math.round(Math.random() * (garmentList.length() - 1)).toInt()
        return garmentList.getJSONObject(randomIndex)
    }

    private fun displayGarment(garment: JSONObject) {
        detailTitle.setText(garment.getString("title"))
        detailPrice.setText("\$${garment.getString("price")}")

        val escapedHtmlText: String = Html.fromHtml(garment.getString("description")).toString()
        detailDescription.setText(Html.fromHtml(escapedHtmlText))

        val imageUri = "@drawable/${garment.getString("image")}"
        val imageResource = resources.getIdentifier(imageUri, null, packageName)
        detailImage.setImageResource(imageResource)
    }


}
