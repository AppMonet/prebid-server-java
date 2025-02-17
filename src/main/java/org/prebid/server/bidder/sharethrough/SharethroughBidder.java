package org.prebid.server.bidder.sharethrough;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Regs;
import com.iab.openrtb.response.Bid;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.lang3.BooleanUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.bidder.sharethrough.model.SharethroughRequestBody;
import org.prebid.server.bidder.sharethrough.model.Size;
import org.prebid.server.bidder.sharethrough.model.StrUriParameters;
import org.prebid.server.bidder.sharethrough.model.UserInfo;
import org.prebid.server.bidder.sharethrough.model.bidresponse.ExtImpSharethroughCreative;
import org.prebid.server.bidder.sharethrough.model.bidresponse.ExtImpSharethroughResponse;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtRegs;
import org.prebid.server.proto.openrtb.ext.request.sharethrough.ExtData;
import org.prebid.server.proto.openrtb.ext.request.sharethrough.ExtImpSharethrough;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SharethroughBidder implements Bidder<SharethroughRequestBody> {

    private static final String VERSION = "8";
    private static final String SUPPLY_ID = "FGMrCMMc";
    private static final String DEFAULT_BID_CURRENCY = "USD";
    private static final BidType DEFAULT_BID_TYPE = BidType.banner;
    private static final Date TEST_TIME = new Date(1604455678999L);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private static final TypeReference<ExtPrebid<?, ExtImpSharethrough>> SHARETHROUGH_EXT_TYPE_REFERENCE =
            new TypeReference<ExtPrebid<?, ExtImpSharethrough>>() {
            };

    private final String endpointUrl;
    private final JacksonMapper mapper;

    private final SharethroughRequestUtil requestUtil;

    public SharethroughBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);

        this.requestUtil = new SharethroughRequestUtil();
    }

    @Override
    public Result<List<HttpRequest<SharethroughRequestBody>>> makeHttpRequests(BidRequest request) {
        final String page = requestUtil.getPage(request.getSite());

        // site.page validation is already performed by {@link RequestValidator#validate}
        if (page == null) {
            return Result.withError(BidderError.badInput("site.page is required"));
        }

        final boolean test = Objects.equals(request.getTest(), 1);
        final Date date = test ? TEST_TIME : new Date();

        List<StrUriParameters> strUriParameters;
        try {
            strUriParameters = parseBidRequestToUriParameters(request, date, test);
        } catch (IllegalArgumentException e) {
            return Result.withError(BidderError.badInput(
                    String.format("Error occurred parsing sharethrough parameters %s", e.getMessage())));
        }
        final MultiMap headers = makeHeaders(request.getDevice(), page);

        final List<BidderError> errors = new ArrayList<>();
        final List<HttpRequest<SharethroughRequestBody>> httpRequests = strUriParameters.stream()
                .map(strUriParameter -> makeHttpRequest(headers, date, strUriParameter, errors))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        return Result.of(httpRequests, errors);
    }

    /**
     * Retrieves from {@link Imp} and filter not valid {@link ExtImpSharethrough} and returns list result with errors.
     */
    private List<StrUriParameters> parseBidRequestToUriParameters(BidRequest request, Date date, boolean test) {
        final Regs regs = request.getRegs();
        final ExtRegs extRegs = regs != null ? regs.getExt() : null;
        final boolean consentRequired = requestUtil.isConsentRequired(extRegs);
        final String usPrivacy = requestUtil.usPrivacy(extRegs);
        final UserInfo userInfo = requestUtil.getUserInfo(request.getUser());
        final String ttdUid = requestUtil.retrieveFromUserInfo(userInfo, UserInfo::getTtdUid);
        final String consent = requestUtil.retrieveFromUserInfo(userInfo, UserInfo::getConsent);
        final String stxuid = requestUtil.retrieveFromUserInfo(userInfo, UserInfo::getStxuid);

        final boolean canAutoPlay = requestUtil.canBrowserAutoPlayVideo(request.getDevice().getUa());

        final long tmax = request.getTmax();
        final List<String> badv = request.getBadv();
        final Date deadLine = new Date(date.getTime() + tmax);

        final List<StrUriParameters> strUriParameters = new ArrayList<>();
        for (Imp imp : request.getImp()) {
            final ExtImpSharethrough extImpStr = mapper.mapper().convertValue(imp.getExt(),
                    SHARETHROUGH_EXT_TYPE_REFERENCE).getBidder();
            final SharethroughRequestBody body = SharethroughRequestBody.of(badv, tmax, DATE_FORMAT.format(deadLine),
                    test, extImpStr.getBidfloor());
            strUriParameters.add(createStrUriParameters(extImpStr, imp, consentRequired, consent, usPrivacy,
                    canAutoPlay, ttdUid, stxuid, body));
        }
        return strUriParameters;
    }

    /**
     * Populate {@link StrUriParameters} with publisher request, imp, imp.ext values.
     */
    private StrUriParameters createStrUriParameters(ExtImpSharethrough extImpSharethrough, Imp imp,
                                                    boolean isConsentRequired,
                                                    String consentString, String usPrivacy,
                                                    boolean canBrowserAutoPlayVideo, String ttdUid, String buyeruid,
                                                    SharethroughRequestBody body) {
        final Size size = requestUtil.getSize(imp, extImpSharethrough);
        final ExtData extData = extImpSharethrough.getData();
        return StrUriParameters.builder()
                .pkey(extImpSharethrough.getPkey())
                .bidID(imp.getId())
                .gpid(extData != null ? extData.getPbAdSlot() : null)
                .consentRequired(isConsentRequired)
                .consentString(consentString)
                .usPrivacySignal(usPrivacy)
                .instantPlayCapable(canBrowserAutoPlayVideo)
                .iframe(extImpSharethrough.getIframe())
                .height(size.getHeight())
                .width(size.getWidth())
                .theTradeDeskUserId(ttdUid)
                .sharethroughUserId(buyeruid)
                .body(body)
                .build();
    }

    /**
     * Make Headers for request.
     */
    private MultiMap makeHeaders(Device device, String page) {
        final MultiMap headers = HttpUtil.headers()
                .add("Origin", requestUtil.getHost(page))
                .add("Referer", page);
        final String ip = device.getIp();
        if (ip != null) {
            headers.add("X-Forwarded-For", ip);
        }

        final String ua = device.getUa();
        if (ua != null) {
            headers.add("User-Agent", ua);
        }
        return headers;
    }

    /**
     * Make {@link HttpRequest} from uri and headers.
     */
    private HttpRequest<SharethroughRequestBody> makeHttpRequest(
            MultiMap headers, Date date, StrUriParameters strUriParameter, List<BidderError> errors) {

        final String uri;
        try {
            uri = SharethroughUriBuilderUtil.buildSharethroughUrl(
                    endpointUrl, SUPPLY_ID, VERSION, DATE_FORMAT.format(date), strUriParameter);
        } catch (IllegalArgumentException e) {
            errors.add(BidderError.badInput(e.getMessage()));
            return null;
        }

        final SharethroughRequestBody body = strUriParameter.getBody();

        return HttpRequest.<SharethroughRequestBody>builder()
                .method(HttpMethod.POST)
                .uri(uri)
                .headers(headers)
                .body(mapper.encodeToBytes(body))
                .payload(body)
                .build();
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<SharethroughRequestBody> httpCall, BidRequest bidRequest) {
        try {
            final String responseBody = httpCall.getResponse().getBody();
            final ExtImpSharethroughResponse sharethroughBid = mapper.mapper().readValue(responseBody,
                    ExtImpSharethroughResponse.class);
            return Result.withValues(toBidderBid(responseBody, sharethroughBid, httpCall.getRequest()));
        } catch (IOException | IllegalArgumentException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    /**
     * Converts {@link ExtImpSharethroughResponse} to {@link List} of {@link BidderBid}.
     */
    private List<BidderBid> toBidderBid(String responseBody, ExtImpSharethroughResponse sharethroughBid,
                                        HttpRequest<SharethroughRequestBody> request) {
        if (sharethroughBid.getCreatives().isEmpty()) {
            throw new IllegalArgumentException("No creative provided");
        }

        final StrUriParameters strUriParameters = SharethroughUriBuilderUtil
                .buildSharethroughUrlParameters(request.getUri());

        final Date date = BooleanUtils.toBoolean(request.getPayload().getTest()) ? TEST_TIME : new Date();
        final String adMarkup = SharethroughMarkupUtil.getAdMarkup(responseBody, sharethroughBid, strUriParameters,
                date);

        final ExtImpSharethroughCreative creative = sharethroughBid.getCreatives().get(0);
        return Collections.singletonList(
                BidderBid.of(
                        Bid.builder()
                                .adid(sharethroughBid.getAdserverRequestId())
                                .id(sharethroughBid.getBidId())
                                .impid(strUriParameters.getBidID())
                                .price(creative.getCpm())
                                .cid(creative.getMetadata().getCampaignKey())
                                .crid(creative.getMetadata().getCreativeKey())
                                .dealid(creative.getMetadata().getDealId())
                                .adm(adMarkup)
                                .h(strUriParameters.getHeight())
                                .w(strUriParameters.getWidth())
                                .build(),
                        DEFAULT_BID_TYPE,
                        DEFAULT_BID_CURRENCY));
    }
}
