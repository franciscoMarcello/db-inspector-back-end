package com.chico.dbinspector.web

import com.chico.dbinspector.util.UpstreamUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class UpstreamContextResolver(
    @Value("\${dbinspector.allowLocalhost:true}") private val allowLocalhost: Boolean,
    @Value("\${dbinspector.requirePathSuffix:/sql/exec/}") private val requirePathSuffix: String
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter) =
        parameter.parameterType == UpstreamContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): Any {
        val url = webRequest.getHeader("X-SQL-EXEC-URL") ?: error("Header X-SQL-EXEC-URL obrigatório")
        UpstreamUtils.validateExternalUrl(url, allowLocalhost = allowLocalhost, requirePathSuffix = requirePathSuffix)

        val auth = webRequest.getHeader(HttpHeaders.AUTHORIZATION)
        val apiToken = webRequest.getHeader("X-API-Token")
        val bearer = UpstreamUtils.resolveBearer(auth, apiToken)

        return UpstreamContext(url, bearer)
    }

    private fun NativeWebRequest.getHeader(name: String) = this.getHeader(name)
}
