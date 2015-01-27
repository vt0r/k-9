package com.fsck.k9.endtoend.pages;

import com.fsck.k9.R;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.matcher.ViewMatchers.withId;

public class WelcomeMessagePage extends AbstractPage {

    public final AccountSetupPage clickNext() {
        onView(withId(R.id.next)).perform(click());
        return new AccountSetupPage();
    }
}
