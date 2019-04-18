package be.valuya.bob.core.api.troll;

import be.valuya.accountingtroll.AccountingEventListener;
import be.valuya.accountingtroll.AccountingManager;
import be.valuya.accountingtroll.domain.ATAccount;
import be.valuya.accountingtroll.domain.ATAccountingEntry;
import be.valuya.accountingtroll.domain.ATBookPeriod;
import be.valuya.accountingtroll.domain.ATBookYear;
import be.valuya.accountingtroll.domain.ATDocument;
import be.valuya.accountingtroll.domain.ATPeriodType;
import be.valuya.accountingtroll.domain.ATTax;
import be.valuya.accountingtroll.domain.ATThirdParty;
import be.valuya.accountingtroll.domain.ATThirdPartyType;
import be.valuya.bob.core.BobAccount;
import be.valuya.bob.core.BobAccountHistoryEntry;
import be.valuya.bob.core.BobCompany;
import be.valuya.bob.core.BobCompanyHistoryEntry;
import be.valuya.bob.core.BobException;
import be.valuya.bob.core.BobFileConfiguration;
import be.valuya.bob.core.BobPeriod;
import be.valuya.bob.core.BobTheTinker;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MemoryCachingBobAccountingManager implements AccountingManager {

    private static final Comparator<BobPeriod> BOB_PERIOD_COMPARATOR = Comparator.comparing(BobPeriod::getfYear)
            .thenComparing(BobPeriod::getYear)
            .thenComparing(BobPeriod::getMonth);

    private final BobTheTinker bobTheTinker;

    private final BobFileConfiguration bobFileConfiguration;
    private Map<String, ATBookYear> bookYears;
    private Map<ATBookYear, List<ATBookPeriod>> bookPeriods;
    private Map<String, ATAccount> accounts;
    private Map<String, ATThirdParty> thirdParties;

    public MemoryCachingBobAccountingManager(BobFileConfiguration bobFileConfiguration) {
        bobTheTinker = new BobTheTinker();
        this.bobFileConfiguration = bobFileConfiguration;
    }

    @Override
    public Optional<LocalDateTime> getLastAccountModificationTime() {
        return bobTheTinker.getLastAccountModifiactionDate(bobFileConfiguration);
    }

    @Override
    public Stream<ATAccount> streamAccounts() {
        return bobTheTinker.readAccounts(bobFileConfiguration)
                .filter(this::isValidAccount)
                .map(this::convertToTrollAccount);
    }

    @Override
    public Stream<ATBookYear> streamBookYears() {
        // Seems periods are grouped by the 'fYear' field which can span more than 1 civil year.
        return bobTheTinker.readPeriods(bobFileConfiguration)
                .sorted(BOB_PERIOD_COMPARATOR)
                .filter(isValidBookYearPeriod())
                .collect(Collectors.groupingBy(BobPeriod::getfYear))
                .entrySet()
                .stream()
                .map(entry -> this.convertToTrollBookYear(entry.getKey(), entry.getValue()));
    }


    @Override
    public Stream<ATBookPeriod> streamPeriods() {
        return bobTheTinker.readPeriods(bobFileConfiguration)
                .filter(this::isValidPeriod)
                .map(this::convertToTrollPeriod);
    }

    @Override
    public Stream<ATThirdParty> streamThirdParties() {
        return bobTheTinker.readCompanies(bobFileConfiguration)
                .filter(this::isValidCompany)
                .flatMap(this::convertToTrollThirdParties);
    }

    @Override
    public Stream<ATAccountingEntry> streamAccountingEntries(AccountingEventListener accountingEventListener) {
        return bobTheTinker.readAccountHistoryEntries(bobFileConfiguration)
                .filter(this::isValidHistoryEntry)
                .flatMap(entry -> convertWithBalanceCheck(entry, accountingEventListener));
    }

//
//    @Override
//    public Stream<ATAccountingEntry> streamAccountingEntries(AccountingEventListener accountingEventListener) {
//        return bobTheTinker.readCompanyHistoryEntries(bobFileConfiguration)
//                .filter(this::isValidHistoryEntry)
//                .flatMap(entry -> convertWithBalanceCheck(entry, accountingEventListener));
//    }

    private boolean isValidAccount(BobAccount bobAccount) {
        String aid = bobAccount.getAid();
        return aid != null && isNotEmptyString(aid.trim());
    }

    private boolean isValidCompany(BobCompany bobCompany) {
        String cid = bobCompany.getcId();
        Optional<String> name1Optional = bobCompany.getcName1Optional();
        return cid != null && isNotEmptyString(cid.trim()) && name1Optional.isPresent();
    }

    private boolean isValidPeriod(BobPeriod bobPeriod) {
        String label = bobPeriod.getLabel();
        return label != null && isNotEmptyString(label.trim());
    }

    private Predicate<BobPeriod> isValidBookYearPeriod() {
        return p -> p.getMonth() > 0;
    }

    private boolean isValidHistoryEntry(BobAccountHistoryEntry historyEntry) {
        String hid = historyEntry.getHid();
        Optional<Integer> hmonthOptional = historyEntry.getHmonthOptional();
        Optional<Integer> hyearOptional = historyEntry.getHyearOptional();
        Optional<String> hdbkOptional = historyEntry.getHdbkOptional();
        Optional<BigDecimal> hamountOptional = historyEntry.getHamountOptional();
        return hid != null && isNotEmptyString(hid.trim())
                && hmonthOptional.isPresent()
                && hyearOptional.isPresent()
                && hdbkOptional.isPresent()
                && hamountOptional.isPresent()
                ;
    }


    private boolean isValidHistoryEntry(BobCompanyHistoryEntry historyEntry) {
        String hid = historyEntry.getHid();
        String htype = historyEntry.getHtype();
        String hfyear = historyEntry.getHfyear();
        Optional<String> hdbkOptional = historyEntry.getHdbk();
        return hid != null && isNotEmptyString(hid.trim())
                && htype != null && isNotEmptyString(htype.trim())
                && hfyear != null && isNotEmptyString(hfyear.trim())
                && hdbkOptional.isPresent()
                ;
    }

    private Stream<ATAccountingEntry> convertWithBalanceCheck(BobAccountHistoryEntry entry, AccountingEventListener accountingEventListener) {
        ATAccountingEntry atAccountingEntry = convertToTrollAccountingEntry(entry);
        return Stream.of(atAccountingEntry);
    }

    private Stream<ATAccountingEntry> convertWithBalanceCheck(BobCompanyHistoryEntry entry, AccountingEventListener accountingEventListener) {
        ATAccountingEntry atAccountingEntry = convertToTrollAccountingEntry(entry);
        return Stream.of(atAccountingEntry);
    }

    private ATAccount convertToTrollAccount(BobAccount bobAccount) {
        String accountNumber = bobAccount.getAid();
        String name = bobAccount.getHeading1Optional()
                .orElse("-");

        return createAccount(accountNumber, name);
    }

    private ATAccount createAccount(String accountNumber, String name) {
        boolean yearResetAccount = isYearResetAccount(accountNumber);

        ATAccount account = new ATAccount();
        account.setCode(accountNumber);
        account.setName(name);
        account.setYearlyBalanceReset(yearResetAccount);
        return account;
    }

    private boolean isYearResetAccount(String accountCode) {
        return accountCode.startsWith("6") || accountCode.startsWith("7");
    }

    private ATBookYear convertToTrollBookYear(String fYear, List<BobPeriod> periods) {
        BobPeriod firstPeriod = periods.stream()
                .filter(this::isValidPeriod)
                .filter(p -> p.getMonth() > 0)
                .findFirst()
                .orElseThrow(IllegalStateException::new);
        BobPeriod lastPeriod = periods.get(periods.size() - 1);
        LocalDate periodStartDate = getPeriodStartDate(firstPeriod);
        LocalDate periodEndDate = getPeriodEndDate(lastPeriod);

        ATBookYear bookYear = new ATBookYear();
        bookYear.setName(fYear);
        bookYear.setStartDate(periodStartDate);
        bookYear.setEndDate(periodEndDate);

        return bookYear;
    }


    private ATBookPeriod convertToTrollPeriod(BobPeriod bobPeriod) {
        String yearName = bobPeriod.getfYear();
        ATBookYear bookYear = findBookYearByName(yearName)
                .orElseThrow(() -> new BobException("No book year matching " + yearName));
        String periodShortName = bobPeriod.getLabel();
        int month = bobPeriod.getMonth();

        LocalDate periodStartDate;
        LocalDate periodEndDate;
        ATPeriodType periodType;
        if (month == 0) {
            periodStartDate = bookYear.getStartDate();
            periodEndDate = periodStartDate.plusMonths(1); // FIXME: to mimic winbooks, but might not be monthly periods
            periodType = ATPeriodType.OPENING; // Its a wildcard period apparently covering the book year
        } else {
            periodStartDate = getPeriodStartDate(bobPeriod);
            periodEndDate = getPeriodEndDate(bobPeriod);
            periodType = ATPeriodType.GENERAL;
        }

        ATBookPeriod bookPeriod = new ATBookPeriod();
        bookPeriod.setBookYear(bookYear);
        bookPeriod.setName(periodShortName);
        bookPeriod.setStartDate(periodStartDate);
        bookPeriod.setEndDate(periodEndDate);
        bookPeriod.setPeriodType(periodType);
        return bookPeriod;
    }


    private Stream<ATThirdParty> convertToTrollThirdParties(BobCompany bobCompany) {
        // A single company for both client/suppliers, while a thirdparty has a single purpose
        Optional<String> cVatNumberOptional = bobCompany.getcVatNumberOptional();
        Optional<String> sVatNumberOptional = bobCompany.getsVatNumberOptional();

        ATThirdParty clientThirdParty = createBaseThirdParty(bobCompany);
        clientThirdParty.setType(ATThirdPartyType.CLIENT);
        cVatNumberOptional.ifPresent(clientThirdParty::setVatNumber);

        ATThirdParty supplierThirdParty = createBaseThirdParty(bobCompany);
        supplierThirdParty.setType(ATThirdPartyType.SUPPLIER);
        sVatNumberOptional.ifPresent(supplierThirdParty::setVatNumber);

        return Stream.of(clientThirdParty, supplierThirdParty);
    }

    private ATThirdParty createBaseThirdParty(BobCompany bobCompany) {
        String id = bobCompany.getcId();
        Optional<String> fullName = bobCompany.getcName1Optional().filter(this::isNotEmptyString);
        Optional<String> address = bobCompany.getcAddress1Optional().filter(this::isNotEmptyString);
        Optional<String> zipCode = bobCompany.getcZipCodeOptional().filter(this::isNotEmptyString);
        Optional<String> city = bobCompany.getcLocalityOptional().filter(this::isNotEmptyString);
        Optional<String> countryCode = bobCompany.getcCountryCodeOptional().filter(this::isNotEmptyString);
        Optional<String> phoneNumber = bobCompany.getcPhoneNumberOptional().filter(this::isNotEmptyString);
        Optional<String> bankAccount = bobCompany.getcBankNumberOptional().filter(this::isNotEmptyString);
        Optional<String> languageOptional = bobCompany.getcLanguageOptional().filter(this::isNotEmptyString);

        ATThirdParty baseThirdParty = new ATThirdParty();
        baseThirdParty.setId(id);
        baseThirdParty.setFullName(fullName.orElse(null));
        baseThirdParty.setAddress(address.orElse(null));
        baseThirdParty.setZipCode(zipCode.orElse(null));
        baseThirdParty.setCity(city.orElse(null));
        baseThirdParty.setCountryCode(countryCode.orElse(null));
        baseThirdParty.setPhoneNumber(phoneNumber.orElse(null));
        baseThirdParty.setBankAccountNumber(bankAccount.orElse(null));
        baseThirdParty.setLanguage(languageOptional.orElse(null));
        return baseThirdParty;
    }

    private boolean isNotEmptyString(String s) {
        return !s.trim().isEmpty();
    }

    private ATAccountingEntry convertToTrollAccountingEntry(BobAccountHistoryEntry entry) {
        ATBookYear bookYear = entry.getHfyearOptional()
                .flatMap(this::findBookYearByName)
                .orElseThrow(() -> new BobException("No book year found with name " + entry.getHfyearOptional()));

        ATBookPeriod bookPeriod = listYearPeriods(bookYear).stream()
                .filter(p -> this.isSameBookYearPeriod(p, entry))
                .findAny()
                .orElseThrow(() -> new BobException("No period found in entry for book year " + bookYear));

        String dbkCode = entry.getHdbkOptional().orElseThrow(() -> new BobException("No dbk entry for accounting entry"));

        BigDecimal amount = entry.getHamountOptional().orElseThrow(() -> new BobException("No amount for entry"));

        String accountNumber = entry.getHid();
        Optional<ATAccount> accountOptional = this.findAccountByCode(accountNumber);

        Optional<String> thirdPartyName = entry.getHcussupOptional();
        ATThirdPartyType thirdPartyType = entry.getHcstypeOptional()
                .flatMap(this::parseThirdPartyTypeFromHSType)
                .orElse(ATThirdPartyType.CLIENT);
        Optional<ATThirdParty> thirdPartyOptional = thirdPartyName
                .flatMap(name -> this.findThirdPartyByIdAndType(name, thirdPartyType));

        LocalDate entryDate = entry.getHdocdateOptional().orElseThrow(() -> new BobException("No date for entry"));
        Optional<LocalDate> documentDateOptional = entry.getHdocdateOptional();
        Optional<LocalDate> dueDateOptional = entry.getHduedateOptional();
        Optional<String> commentOptional = entry.getHremOptional();

        Optional<ATTax> taxOptional = Optional.empty(); //TODO
        Optional<ATDocument> documentOptional = Optional.empty(); // TODO

        ATAccountingEntry accountingEntry = new ATAccountingEntry();
        accountingEntry.setBookPeriod(bookPeriod);
        accountingEntry.setDate(entryDate);
        accountingEntry.setAmount(amount);
        accountingEntry.setDbkCode(dbkCode);

        accountingEntry.setAccountOptional(accountOptional);
        accountingEntry.setThirdPartyOptional(thirdPartyOptional);
        accountingEntry.setDocumentDateOptional(documentDateOptional);
        accountingEntry.setDueDateOptional(dueDateOptional);
        accountingEntry.setCommentOptional(commentOptional);
        accountingEntry.setTaxOptional(taxOptional);
        accountingEntry.setDocumentOptional(documentOptional);

        return accountingEntry;
    }


    private ATAccountingEntry convertToTrollAccountingEntry(BobCompanyHistoryEntry entry) {
        String hfyear = entry.getHfyear();
        ATBookYear bookYear = findBookYearByName(hfyear)
                .orElseThrow(() -> new BobException("No book year found with name " + hfyear));

        ATBookPeriod bookPeriod = listYearPeriods(bookYear).stream()
                .filter(p -> this.isSameBookYearPeriod(p, entry))
                .findAny()
                .orElseThrow(() -> new BobException("No period found in entry for book year " + bookYear + " and month " + entry.getHmmonth() + " and year  " + entry.getHyear()));

        String hdbk = entry.getHdbk().orElseThrow(() -> new BobException("No dbk for entry"));
        BigDecimal amount = entry.getHamount().orElseThrow(() -> new BobException("No amount for entry"));

        Optional<ATAccount> accountOptional = entry.getHcollectacc()
                .flatMap(this::findAccountByCode);

        String hid = entry.getHid();
        Optional<ATThirdParty> thirdPartyOptional = this.findThirdPartyByIdAndType(hid, ATThirdPartyType.CLIENT);// FIXME: client or suppplier?

        LocalDate entryDate = entry.getHdocdate().orElseThrow(() -> new BobException("No date for entry"));
        Optional<LocalDate> documentDateOptional = entry.getHdocdate();
        Optional<LocalDate> dueDateOptional = entry.getHduedate();
        Optional<String> commentOptional = Optional.empty();
        Optional<Integer> hmatchno = entry.getHmatchno();

        Optional<ATTax> taxOptional = Optional.empty(); //TODO
        Optional<ATDocument> documentOptional = Optional.empty(); // TODO
        Optional<ATDocument> matchedDocumentOptional = Optional.empty(); //TODO

        ATAccountingEntry accountingEntry = new ATAccountingEntry();
        accountingEntry.setBookPeriod(bookPeriod);
        accountingEntry.setDate(entryDate);
        accountingEntry.setAmount(amount);
        accountingEntry.setDbkCode(hdbk);
        accountingEntry.setMatched(hmatchno.isPresent());

        accountingEntry.setAccountOptional(accountOptional);
        accountingEntry.setThirdPartyOptional(thirdPartyOptional);
        accountingEntry.setDocumentDateOptional(documentDateOptional);
        accountingEntry.setDueDateOptional(dueDateOptional);
        accountingEntry.setCommentOptional(commentOptional);
        accountingEntry.setTaxOptional(taxOptional);
        accountingEntry.setDocumentOptional(documentOptional);
        accountingEntry.setMatchedDocumentOptional(matchedDocumentOptional);

        return accountingEntry;
    }

    private Optional<ATThirdPartyType> parseThirdPartyTypeFromHSType(String hsType) {
        switch (hsType) {
            case "":
                return Optional.empty();
            case "S":
                return Optional.of(ATThirdPartyType.SUPPLIER);
            default:
                throw new BobException("Unhandled hsType: " + hsType);
        }
    }


    private boolean isSameBookYearPeriod(ATBookPeriod bookPeriod, BobAccountHistoryEntry accountingEntry) {
        int year = accountingEntry.getHyearOptional().orElseThrow(() -> new BobException("No year set in entry"));
        int month = accountingEntry.getHmonthOptional().orElseThrow(() -> new BobException("No month set in entry"));
        return isSamePeriod(bookPeriod, year, month);
    }

    private boolean isSameBookYearPeriod(ATBookPeriod bookPeriod, BobCompanyHistoryEntry historyEntry) {
        int year = historyEntry.getHyear().orElseThrow(() -> new BobException("No year set in entry"));
        int month = historyEntry.getHmonth().orElseThrow(() -> new BobException("No month set in entry"));
        return isSamePeriod(bookPeriod, year, month);
    }

    private boolean isSamePeriod(ATBookPeriod bookPeriod, int year, int month) {
        if (month == 0) {
            return bookPeriod.getPeriodType() == ATPeriodType.OPENING;
        } else {
            if (bookPeriod.getPeriodType() != ATPeriodType.GENERAL) {
                return false;
            }
        }
        LocalDate startDate = bookPeriod.getStartDate();
        int periodYear = startDate.get(ChronoField.YEAR);
        int periodMonth = startDate.get(ChronoField.MONTH_OF_YEAR);
        return periodYear == year && periodMonth == month;
    }


    private LocalDate getPeriodEndDate(BobPeriod period) {
        int lastYear = period.getYear();
        int lastMonth = period.getMonth();
        return LocalDate.now().withYear(lastYear).withMonth(lastMonth).withDayOfMonth(1)
                .plusMonths(1);
    }

    private LocalDate getPeriodStartDate(BobPeriod period) {
        int firstYear = period.getYear();
        int firstMonth = period.getMonth();
        return LocalDate.now().withYear(firstYear).withMonth(firstMonth).withDayOfMonth(1);
    }


    private List<ATBookYear> listBookYears() {
        return new ArrayList<>(getBookYears().values());
    }

    private List<ATBookPeriod> listPeriods() {
        return getBookPeriods().values()
                .stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private List<ATBookPeriod> listYearPeriods(ATBookYear bookYear) {
        List<ATBookPeriod> periodsNullable = getBookPeriods().get(bookYear);
        return Optional.ofNullable(periodsNullable).orElseGet(ArrayList::new);
    }

    private Optional<ATBookYear> findBookYearByName(String name) {
        ATBookYear bookYearNullable = getBookYears().get(name);
        return Optional.ofNullable(bookYearNullable);
    }

    private Optional<ATBookPeriod> findPeriodByName(ATBookYear bookYear, String name) {
        List<ATBookPeriod> bookYearPeriods = getBookPeriods().get(bookYear);
        return bookYearPeriods.stream()
                .filter(p -> p.getName().equals(name))
                .findAny();
    }

    private ATAccount findOrCreateAccountByCode(String code) {
        ATAccount accountNullable = getAccounts().get(code);
        return Optional.ofNullable(accountNullable)
                .orElseGet(() -> createAccount(code, "<ABSENT_FROM_ACCOUNT_TABLE>"));
    }

    private Optional<ATAccount> findAccountByCode(String code) {
        ATAccount accountNullable = getAccounts().get(code);
        return Optional.ofNullable(accountNullable);
    }

    private Optional<ATThirdParty> findThirdPartyByIdAndType(String id, ATThirdPartyType type) {
        String typedThirdPartyId = this.getTypedThirdPartyId(id, type);
        ATThirdParty thirdPartyNullable = getThirdParties().get(typedThirdPartyId);
        return Optional.ofNullable(thirdPartyNullable);
    }

    private String getTypedThirdPartyId(String id, ATThirdPartyType type) {
        return MessageFormat.format("{0}::{1}", type, id);
    }

    private String getTypedThirdPartyId(ATThirdParty thirdParty) {
        String id = thirdParty.getId();
        ATThirdPartyType type = thirdParty.getTypeOptional().orElse(ATThirdPartyType.CLIENT);
        return getTypedThirdPartyId(id, type);
    }


    private Map<String, ATBookYear> getBookYears() {
        if (bookYears == null) {
            readBookYears();
        }
        return bookYears;
    }

    private Map<ATBookYear, List<ATBookPeriod>> getBookPeriods() {
        if (bookPeriods == null) {
            readPeriods();
        }
        return this.bookPeriods;
    }


    private Map<String, ATAccount> getAccounts() {
        if (accounts == null) {
            readAccounts();
        }
        return accounts;
    }


    private Map<String, ATThirdParty> getThirdParties() {
        if (thirdParties == null) {
            readThirdParties();
        }
        return thirdParties;
    }

    private void readPeriods() {
        this.bookPeriods = streamPeriods()
                .collect(Collectors.groupingBy(ATBookPeriod::getBookYear));
    }

    private void readBookYears() {
        this.bookYears = streamBookYears()
                .collect(Collectors.toMap(
                        ATBookYear::getName,
                        Function.identity()
                ));
    }


    private void readAccounts() {
        this.accounts = streamAccounts()
                .collect(Collectors.toMap(
                        ATAccount::getCode,
                        Function.identity()
                ));
    }


    private void readThirdParties() {
        this.thirdParties = streamThirdParties()
                .collect(Collectors.toMap(
                        this::getTypedThirdPartyId,
                        Function.identity()
                ));
    }
}
