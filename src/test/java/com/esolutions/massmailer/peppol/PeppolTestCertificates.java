package com.esolutions.massmailer.peppol;

/**
 * Shared test X.509 certificate and RSA private key for PEPPOL tests.
 *
 * <p>Pre-generated with:
 * {@code openssl req -x509 -newkey rsa:2048 -days 3650 -nodes
 *       -subj "/CN=Test PEPPOL AP/O=Invoicedirect/C=ZW"}
 */
public final class PeppolTestCertificates {

    public static final String PEM_CERT = """
            -----BEGIN CERTIFICATE-----
            MIIDXTCCAkWgAwIBAgIUMWskf0YMK/rpzArQkRTyS3zoSHgwDQYJKoZIhvcNAQEL
            BQAwPjEXMBUGA1UEAwwOVGVzdCBQRVBQT0wgQVAxFjAUBgNVBAoMDUludm9pY2Vk
            aXJlY3QxCzAJBgNVBAYTAlpXMB4XDTI2MDYxOTEzNDIxN1oXDTM2MDYxNjEzNDIx
            N1owPjEXMBUGA1UEAwwOVGVzdCBQRVBQT0wgQVAxFjAUBgNVBAoMDUludm9pY2Vk
            aXJlY3QxCzAJBgNVBAYTAlpXMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC
            AQEAl8bhJKjmvzFozlY3qm/e/69KAiZJ0TcCRBUlT/+oX3dbPhAzOBtvgOukTW3g
            iHeAWVou10hXK1OgtPxatW/rfVmEJBXq/uJdhKZ6fwpqd33SdSRm3TCluMwwAHaN
            /pXH4nHA/BG+4Anq7qgZ0uJu204XkPlqnBUXrVCS2JHu1NaUXrksBnE+A/U8YDG7
            SaScqQDPV8f9cb1QZTFgImGytmOm97traHDbORYGBbdCbCRt+huoUqOJ8YRP5wAt
            +HyxN/n1DDJ86WZp3+eWVluleoto4oGfrDqcMpndbV664wTDLaU3MViMildWx1l+
            052kouyBMfNyGONP7M2rJhN0cwIDAQABo1MwUTAdBgNVHQ4EFgQUk9p2QbM0Nvhw
            DbCssBtEJTxQEyYwHwYDVR0jBBgwFoAUk9p2QbM0NvhwDbCssBtEJTxQEyYwDwYD
            VR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAidIux9Lh/IFDkmRu1FiF
            cjsH84uWQRazmS53R5CYUuXtoOjF/GkrBtJiySD+7u5TBqovGYoAEXwjFffMsxTt
            LzmsQyVKK1swVPvMO7ikQyZqrk8HavGMp32mwIMrEssf3OFaG+wXSA9teQWb+Pko
            fLfoIgHLlo9sAsFQjekReZxrKTIsc0cg/iUjJ3/H4SgvTsnyC6LmPF+NAgfuvBi0
            Lo19TlILVM/olpOpU+eoKe77MSN8LIlvAVTbiXSFaRG4bCkrMauOAgEVKSfIw4NJ
            GX5DsjQ/ARMMueye5qnq0vSMx9PP876mwZdsOMjB05wUUdvqKO1zc7zPMG/w/x0w
            cQ==
            -----END CERTIFICATE-----""";

    public static final String PEM_KEY = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCXxuEkqOa/MWjO
            Vjeqb97/r0oCJknRNwJEFSVP/6hfd1s+EDM4G2+A66RNbeCId4BZWi7XSFcrU6C0
            /Fq1b+t9WYQkFer+4l2Epnp/Cmp3fdJ1JGbdMKW4zDAAdo3+lcficcD8Eb7gCeru
            qBnS4m7bTheQ+WqcFRetUJLYke7U1pReuSwGcT4D9TxgMbtJpJypAM9Xx/1xvVBl
            MWAiYbK2Y6b3u2tocNs5FgYFt0JsJG36G6hSo4nxhE/nAC34fLE3+fUMMnzpZmnf
            55ZWW6V6i2jigZ+sOpwymd1tXrrjBMMtpTcxWIyKV1bHWX7TnaSi7IEx83IY40/s
            zasmE3RzAgMBAAECggEABWesIk2mpo3QUmDGNLW/dojyy+bM35e/MDJaS6sqWorV
            cWsURZTO0wPIGfXKv58VQb3n/o3MzghUzKHAj33SIFY2LXfxxyNV+qnF6rGx1cfP
            b5FdAh/I8nftUbALs+TTmik8UbSPgNz5jYeesqm3ElpATE7zPkGzwAJwaE0f1FsN
            zNCweu7IxKCWeRlGJIboi2EsNPfKqMpiGNz7Aif5li5IUE5Ju2pwTe3WFF65vwA4
            zGck91mojSbA9PS/fxaFbxMrG+9YeLoNlNwL6PHW9W5EbOT5E19GkKO90V9hcZPA
            oEzzBD0wOhn97RVbUmWstGxgLA00s2BtsTxCK9DYWQKBgQDV+32RLNwnUFMTzMbL
            uE71TJr2QJ16P+GRTiqByxqKTH3ryuaVX1eROICKQ/+8+x9ZcDe7Lk38UZ1ApeCG
            gwGd4tlux1w/ZvDUfXSlaRoRj3SBdJvMsEPAlkPOEgcP2Kb6afVifGLYBnT7ecct
            xHaXv2zj9hLsp2eXgs7NFNDMDQKBgQC1lG4HnAh70l7ERomPTDV1t+DzEk78apQ1
            sztohO0lg2ghFzQa7/4R2QyC7TTqRrrrsmdHRKvPucWDlq6/4ADO7jQTeaRJcTI3
            R8nHJtLdUWu5v9LV4GzL+zsqzE0QZfuS8q46po1i28zKsUOb6QMjqXrA/iygRN9X
            pFcXeVaifwKBgQCSs9fTpyDMZzp90Z/dXV/stvdqqsQMYy1/lEp6L8fjVyvhc3sA
            n5wGeOlPYe08ICLPC6t5zfYzbbTU9KRpM/nwx+zwEQgCg83KsLQ/Dz6LGYHzmwGR
            pKrep2aXKi7jQ1K/TdOpEMQnfM3I9yWCEDaKNPgvla5Fx0OzUT/8xC+eJQKBgQC1
            IND5Kj6UNW9u3uYNWXniaYAtOFAuHqqlPpq+Ugq1ZvWSPLEvRcLtTtPaLoCgSdnf
            j2DSiL/SQH+0GbVLlUl5Icg0vRySXiY3Wpb+381sE/IfoifQTUR53axRruYYu4JO
            PWJrAwT6XkNU1aqZdcA57K+UoHcnkEwyAq8VrWYsRQKBgFul2k8WSMkHzkGu9NHL
            KeEiBD7fRfE/7DhN7d7CiHi6ERn/bPZdaCUYsXJpyJkUvW62p52d8/FrdW9oHAr0
            /zroG3TIh0wB06XZVJrNZ4wWuH/3cUoLFwaLKMmCVoSLHFh/Srzci7UB53axTbQV
            RS+oev2RXi+4owL/OnVL8s8L
            -----END PRIVATE KEY-----""";

    private PeppolTestCertificates() {}
}
